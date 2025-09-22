import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
  private static final int PORTA = 8080;
  private static final int BUFFER_SIZE = 8192;

  private Selector selector;
  private ServerSocketChannel serverChannel;
  private Map<String, ClienteService> clientes;
  private Map<String, Grupo> grupos;
  private boolean executando;

  public Servidor() {
    clientes = new ConcurrentHashMap<>();
    grupos = new ConcurrentHashMap<>();
    executando = false;
  }

  public void iniciar() {
    try {
      selector = Selector.open();
      serverChannel = ServerSocketChannel.open();
      serverChannel.configureBlocking(false);
      serverChannel.bind(new InetSocketAddress(PORTA));
      serverChannel.register(selector, SelectionKey.OP_ACCEPT);

      executando = true;
      System.out.println("Servidor iniciado na porta " + PORTA);
      System.out.println("Aguardando conexoes\n");

      // Loop principal - single thread
      while (executando) {
        int readyChannels = selector.select(1000); // timeout de 1 segundo

        if (readyChannels == 0) {
          continue;
        }

        Set<SelectionKey> selectedKeys = selector.selectedKeys();
        Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

        while (keyIterator.hasNext()) {
          SelectionKey key = keyIterator.next();

          try {
            if (key.isAcceptable()) {
              aceitarConexao(key);
            } else if (key.isReadable()) {
              lerDados(key);
            } else if (key.isWritable()) {
              escreverDados(key);
            }
          } catch (Exception e) {
            System.err.println("Erro ao processar key: " + e.getMessage());
            fecharConexao(key);
          }

          keyIterator.remove();
        }
      }

    } catch (IOException e) {
      System.err.println("Erro ao iniciar servidor: " + e.getMessage());
    } finally {
      parar();
    }
  }

  private void aceitarConexao(SelectionKey key) throws IOException {
    ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
    SocketChannel clientChannel = serverChannel.accept();

    if (clientChannel != null) {
      clientChannel.configureBlocking(false);
      SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);

      ClienteService clienteService = new ClienteService(clientChannel);
      clientKey.attach(clienteService);

      System.out.println("Nova conexão aceita de: " + clientChannel.getRemoteAddress());
    }
  }

  private void lerDados(SelectionKey key) throws IOException {
    SocketChannel clientChannel = (SocketChannel) key.channel();
    ClienteService clienteService = (ClienteService) key.attachment();

    if (clienteService == null) {
      fecharConexao(key);
      return;
    }

    ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
    int bytesRead = clientChannel.read(buffer);

    if (bytesRead == -1) {
      // Cliente desconectou
      fecharConexao(key);
      return;
    }

    if (bytesRead > 0) {
      buffer.flip();
      clienteService.adicionarDados(buffer);

      // Tentar processar mensagens completas
      Mensagem mensagem;
      while ((mensagem = clienteService.lerMensagem()) != null) {
        processarMensagem(mensagem, clienteService, key);
      }
    }
  }

  private void escreverDados(SelectionKey key) throws IOException {
    SocketChannel clientChannel = (SocketChannel) key.channel();
    ClienteService clienteService = (ClienteService) key.attachment();

    if (clienteService == null) {
      fecharConexao(key);
      return;
    }

    ByteBuffer buffer = clienteService.getBufferEscrita();
    if (buffer != null && buffer.hasRemaining()) {
      clientChannel.write(buffer);

      if (!buffer.hasRemaining()) {
        // Escrita completa, remover interesse em escrita
        key.interestOps(SelectionKey.OP_READ);
        clienteService.limparBufferEscrita();
      }
    } else {
      // Nada para escrever
      key.interestOps(SelectionKey.OP_READ);
    }
  }

  private void processarMensagem(Mensagem mensagem, ClienteService clienteService, SelectionKey key) {
    switch (mensagem.getTipo()) {
      case LOGIN:
        login(mensagem, clienteService, key);
        break;
      case LOGOUT:
        logout(clienteService, key);
        break;
      case MENSAGEM_PRIVADA:
        mensagemPrivada(mensagem);
        break;
      case MENSAGEM_GRUPO:
        mensagemGrupo(mensagem);
        break;
      case TRANSFERENCIA_ARQUIVO:
        transferenciaArquivo(mensagem);
        break;
      case CRIAR_GRUPO:
        criarGrupo(mensagem, clienteService);
        break;
      case ENTRAR_GRUPO:
        entrarGrupo(mensagem, clienteService);
        break;
    }
  }

  private void login(Mensagem mensagem, ClienteService clienteService, SelectionKey key) {
    String usuarioSolicitado = mensagem.getRemetente();

    if (usuarioSolicitado == null || usuarioSolicitado.trim().isEmpty() ||
        clientes.containsKey(usuarioSolicitado)) {

      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Nome de usuario já está sendo usado ou é inválido.");
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteService);
      fecharConexao(key);
      return;
    }

    clienteService.setNomeUsuario(usuarioSolicitado);
    clientes.put(usuarioSolicitado, clienteService);

    Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
    resposta.setConteudo("Login realizado com sucesso como: " + usuarioSolicitado);
    enviarMensagem(resposta, clienteService);

    System.out.println("Cliente conectado: " + usuarioSolicitado);
  }

  private void logout(ClienteService clienteService, SelectionKey key) {
    fecharConexao(key);
  }

  private void mensagemPrivada(Mensagem mensagem) {
    ClienteService destinatario = clientes.get(mensagem.getDestinatario());
    ClienteService remetente = clientes.get(mensagem.getRemetente());

    if (destinatario != null && destinatario.isConectado()) {
      enviarMensagem(mensagem, destinatario);

      if (remetente != null) {
        Mensagem confirmacao = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
        confirmacao.setConteudo("Mensagem enviada para " + mensagem.getDestinatario());
        enviarMensagem(confirmacao, remetente);
      }
    } else {
      if (remetente != null) {
        Mensagem erro = new Mensagem(Mensagem.TipoMensagem.ERRO);
        erro.setConteudo("Usuario não encontrado ou offline: " + mensagem.getDestinatario());
        erro.setSucesso(false);
        enviarMensagem(erro, remetente);
      }
    }
  }

  private void mensagemGrupo(Mensagem mensagem) {
    Grupo grupo = grupos.get(mensagem.getNomeGrupo());
    ClienteService remetente = clientes.get(mensagem.getRemetente());

    if (grupo != null && grupo.eMembro(mensagem.getRemetente())) {
      // Enviar para todos os membros do grupo (exceto o remetente)
      for (String membro : grupo.getMembros()) {
        if (!membro.equals(mensagem.getRemetente())) {
          ClienteService membroInfo = clientes.get(membro);
          if (membroInfo != null && membroInfo.isConectado()) {
            enviarMensagem(mensagem, membroInfo);
          }
        }
      }

      if (remetente != null) {
        Mensagem confirmacao = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
        confirmacao.setConteudo("Mensagem enviada para o grupo " + mensagem.getNomeGrupo());
        enviarMensagem(confirmacao, remetente);
      }
    } else {
      if (remetente != null) {
        Mensagem erro = new Mensagem(Mensagem.TipoMensagem.ERRO);
        erro.setConteudo("Grupo não encontrado ou você não é membro: " + mensagem.getNomeGrupo());
        erro.setSucesso(false);
        enviarMensagem(erro, remetente);
      }
    }
  }

  private void transferenciaArquivo(Mensagem mensagem) {
    // Salvar arquivo no servidor
    try {
      String nomeArquivo = mensagem.getNomeArquivo();
      String caminhoArquivo = "arquivos_servidor/" + nomeArquivo;
      java.nio.file.Files.createDirectories(java.nio.file.Paths.get("arquivos_servidor"));
      java.nio.file.Files.write(java.nio.file.Paths.get(caminhoArquivo), mensagem.getDadosArquivo());

      ClienteService remetente = clientes.get(mensagem.getRemetente());

      // Enviar arquivo para destinatário ou grupo
      if (mensagem.getDestinatario() != null) {
        ClienteService destinatario = clientes.get(mensagem.getDestinatario());
        if (destinatario != null && destinatario.isConectado()) {
          enviarMensagem(mensagem, destinatario);
        }
      } else if (mensagem.getNomeGrupo() != null) {
        Grupo grupo = grupos.get(mensagem.getNomeGrupo());
        if (grupo != null && grupo.eMembro(mensagem.getRemetente())) {
          for (String membro : grupo.getMembros()) {
            if (!membro.equals(mensagem.getRemetente())) {
              ClienteService membroInfo = clientes.get(membro);
              if (membroInfo != null && membroInfo.isConectado()) {
                enviarMensagem(mensagem, membroInfo);
              }
            }
          }
        }
      }

      if (remetente != null) {
        Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
        resposta.setConteudo("Arquivo enviado com sucesso: " + nomeArquivo);
        enviarMensagem(resposta, remetente);
      }

    } catch (IOException e) {
      ClienteService remetente = clientes.get(mensagem.getRemetente());
      if (remetente != null) {
        Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
        resposta.setConteudo("ERRO: Falha ao processar arquivo - " + e.getMessage());
        resposta.setSucesso(false);
        enviarMensagem(resposta, remetente);
      }
    }
  }

  private void criarGrupo(Mensagem mensagem, ClienteService clienteService) {
    String nomeGrupo = mensagem.getNomeGrupo();

    if (nomeGrupo == null || nomeGrupo.trim().isEmpty() || grupos.containsKey(nomeGrupo)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Grupo já existe ou nome inválido: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteService);
      return;
    }

    grupos.put(nomeGrupo, new Grupo(nomeGrupo));

    Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
    resposta.setConteudo("Grupo criado com sucesso: " + nomeGrupo);
    enviarMensagem(resposta, clienteService);
  }

  private void entrarGrupo(Mensagem mensagem, ClienteService clienteService) {
    String nomeGrupo = mensagem.getNomeGrupo();
    String usuario = clienteService.getNomeUsuario();

    Grupo grupo = grupos.get(nomeGrupo);
    if (grupo != null && grupo.adicionarMembro(usuario)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Você entrou no grupo: " + nomeGrupo);
      enviarMensagem(resposta, clienteService);
    } else {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Não foi possível entrar no grupo: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteService);
    }
  }

  private void enviarMensagem(Mensagem mensagem, ClienteService clienteService) {
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(mensagem);
      oos.flush();

      byte[] dados = baos.toByteArray();
      ByteBuffer buffer = ByteBuffer.allocate(4 + dados.length);
      buffer.putInt(dados.length); // Tamanho da mensagem
      buffer.put(dados);
      buffer.flip();

      clienteService.adicionarParaEscrita(buffer);

      // Marcar canal para escrita
      SelectionKey key = clienteService.getChannel().keyFor(selector);
      if (key != null && key.isValid()) {
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
      }

    } catch (IOException e) {
      System.err.println("Erro ao preparar mensagem para envio: " + e.getMessage());
    }
  }

  private void fecharConexao(SelectionKey key) {
    try {
      ClienteService clienteService = (ClienteService) key.attachment();

      if (clienteService != null) {
        String nomeUsuario = clienteService.getNomeUsuario();
        if (nomeUsuario != null) {
          clientes.remove(nomeUsuario);

          // Remover usuario de todos os grupos
          for (Grupo grupo : grupos.values()) {
            grupo.removerMembro(nomeUsuario);
          }

          System.out.println("Cliente desconectado: " + nomeUsuario);
        }

        clienteService.fechar();
      }

      key.cancel();
      key.channel().close();

    } catch (IOException e) {
      System.err.println("Erro ao fechar conexão: " + e.getMessage());
    }
  }

  public void parar() {
    executando = false;
    try {
      if (selector != null) {
        selector.close();
      }
      if (serverChannel != null) {
        serverChannel.close();
      }
    } catch (IOException e) {
      System.err.println("Erro ao parar servidor: " + e.getMessage());
    }
  }

  public static void main(String[] args) {
    Servidor servidor = new Servidor();

    // Adicionar shutdown hook para parar o servidor
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nParando servidor...");
      servidor.parar();
    }));

    servidor.iniciar();
  }
}
