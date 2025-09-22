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
  private Map<String, ClienteInfo> clientes;
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
      System.out.println("Servidor NIO iniciado na porta " + PORTA);
      System.out.println("Aguardando conexões...\n");

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

      ClienteInfo clienteInfo = new ClienteInfo(clientChannel);
      clientKey.attach(clienteInfo);

      System.out.println("Nova conexão aceita de: " + clientChannel.getRemoteAddress());
    }
  }

  private void lerDados(SelectionKey key) throws IOException {
    SocketChannel clientChannel = (SocketChannel) key.channel();
    ClienteInfo clienteInfo = (ClienteInfo) key.attachment();

    if (clienteInfo == null) {
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
      clienteInfo.adicionarDados(buffer);

      // Tentar processar mensagens completas
      Mensagem mensagem;
      while ((mensagem = clienteInfo.lerMensagem()) != null) {
        processarMensagem(mensagem, clienteInfo, key);
      }
    }
  }

  private void escreverDados(SelectionKey key) throws IOException {
    SocketChannel clientChannel = (SocketChannel) key.channel();
    ClienteInfo clienteInfo = (ClienteInfo) key.attachment();

    if (clienteInfo == null) {
      fecharConexao(key);
      return;
    }

    ByteBuffer buffer = clienteInfo.getBufferEscrita();
    if (buffer != null && buffer.hasRemaining()) {
      clientChannel.write(buffer);

      if (!buffer.hasRemaining()) {
        // Escrita completa, remover interesse em escrita
        key.interestOps(SelectionKey.OP_READ);
        clienteInfo.limparBufferEscrita();
      }
    } else {
      // Nada para escrever
      key.interestOps(SelectionKey.OP_READ);
    }
  }

  private void processarMensagem(Mensagem mensagem, ClienteInfo clienteInfo, SelectionKey key) {
    switch (mensagem.getTipo()) {
      case LOGIN:
        login(mensagem, clienteInfo, key);
        break;
      case LOGOUT:
        logout(clienteInfo, key);
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
        criarGrupo(mensagem, clienteInfo);
        break;
      case ENTRAR_GRUPO:
        entrarGrupo(mensagem, clienteInfo);
        break;
    }
  }

  private void login(Mensagem mensagem, ClienteInfo clienteInfo, SelectionKey key) {
    String usuarioSolicitado = mensagem.getRemetente();

    if (usuarioSolicitado == null || usuarioSolicitado.trim().isEmpty() ||
        clientes.containsKey(usuarioSolicitado)) {

      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Nome de usuário já está sendo usado ou é inválido.");
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteInfo);
      fecharConexao(key);
      return;
    }

    clienteInfo.setNomeUsuario(usuarioSolicitado);
    clientes.put(usuarioSolicitado, clienteInfo);

    Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
    resposta.setConteudo("Login realizado com sucesso como: " + usuarioSolicitado);
    enviarMensagem(resposta, clienteInfo);

    System.out.println("Cliente conectado: " + usuarioSolicitado);
  }

  private void logout(ClienteInfo clienteInfo, SelectionKey key) {
    fecharConexao(key);
  }

  private void mensagemPrivada(Mensagem mensagem) {
    ClienteInfo destinatario = clientes.get(mensagem.getDestinatario());
    ClienteInfo remetente = clientes.get(mensagem.getRemetente());

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
        erro.setConteudo("Usuário não encontrado ou offline: " + mensagem.getDestinatario());
        erro.setSucesso(false);
        enviarMensagem(erro, remetente);
      }
    }
  }

  private void mensagemGrupo(Mensagem mensagem) {
    Grupo grupo = grupos.get(mensagem.getNomeGrupo());
    ClienteInfo remetente = clientes.get(mensagem.getRemetente());

    if (grupo != null && grupo.eMembro(mensagem.getRemetente())) {
      // Enviar para todos os membros do grupo (exceto o remetente)
      for (String membro : grupo.getMembros()) {
        if (!membro.equals(mensagem.getRemetente())) {
          ClienteInfo membroInfo = clientes.get(membro);
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

      ClienteInfo remetente = clientes.get(mensagem.getRemetente());

      // Enviar arquivo para destinatário ou grupo
      if (mensagem.getDestinatario() != null) {
        ClienteInfo destinatario = clientes.get(mensagem.getDestinatario());
        if (destinatario != null && destinatario.isConectado()) {
          enviarMensagem(mensagem, destinatario);
        }
      } else if (mensagem.getNomeGrupo() != null) {
        Grupo grupo = grupos.get(mensagem.getNomeGrupo());
        if (grupo != null && grupo.eMembro(mensagem.getRemetente())) {
          for (String membro : grupo.getMembros()) {
            if (!membro.equals(mensagem.getRemetente())) {
              ClienteInfo membroInfo = clientes.get(membro);
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
      ClienteInfo remetente = clientes.get(mensagem.getRemetente());
      if (remetente != null) {
        Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
        resposta.setConteudo("ERRO: Falha ao processar arquivo - " + e.getMessage());
        resposta.setSucesso(false);
        enviarMensagem(resposta, remetente);
      }
    }
  }

  private void criarGrupo(Mensagem mensagem, ClienteInfo clienteInfo) {
    String nomeGrupo = mensagem.getNomeGrupo();

    if (nomeGrupo == null || nomeGrupo.trim().isEmpty() || grupos.containsKey(nomeGrupo)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Grupo já existe ou nome inválido: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteInfo);
      return;
    }

    grupos.put(nomeGrupo, new Grupo(nomeGrupo));

    Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
    resposta.setConteudo("Grupo criado com sucesso: " + nomeGrupo);
    enviarMensagem(resposta, clienteInfo);
  }

  private void entrarGrupo(Mensagem mensagem, ClienteInfo clienteInfo) {
    String nomeGrupo = mensagem.getNomeGrupo();
    String usuario = clienteInfo.getNomeUsuario();

    Grupo grupo = grupos.get(nomeGrupo);
    if (grupo != null && grupo.adicionarMembro(usuario)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Você entrou no grupo: " + nomeGrupo);
      enviarMensagem(resposta, clienteInfo);
    } else {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Não foi possível entrar no grupo: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta, clienteInfo);
    }
  }

  private void enviarMensagem(Mensagem mensagem, ClienteInfo clienteInfo) {
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

      clienteInfo.adicionarParaEscrita(buffer);

      // Marcar canal para escrita
      SelectionKey key = clienteInfo.getChannel().keyFor(selector);
      if (key != null && key.isValid()) {
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
      }

    } catch (IOException e) {
      System.err.println("Erro ao preparar mensagem para envio: " + e.getMessage());
    }
  }

  private void fecharConexao(SelectionKey key) {
    try {
      ClienteInfo clienteInfo = (ClienteInfo) key.attachment();

      if (clienteInfo != null) {
        String nomeUsuario = clienteInfo.getNomeUsuario();
        if (nomeUsuario != null) {
          clientes.remove(nomeUsuario);

          // Remover usuário de todos os grupos
          for (Grupo grupo : grupos.values()) {
            grupo.removerMembro(nomeUsuario);
          }

          System.out.println("Cliente desconectado: " + nomeUsuario);
        }

        clienteInfo.fechar();
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
