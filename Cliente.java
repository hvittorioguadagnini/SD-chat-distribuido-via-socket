import java.io.*;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class Cliente {
  private static final String ENDERECO_SERVIDOR = "ec2-3-17-128-71.us-east-2.compute.amazonaws.com";
  private static final int PORTA_SERVIDOR = 8080;

  private SocketChannel socketChannel;
  private String nomeUsuario;
  private boolean conectado;
  private Scanner scanner;
  private Thread threadLeitura;
  private BlockingQueue<Mensagem> filaMensagens;
  private ByteBuffer bufferLeitura;
  private int tamanhoMensagemEsperado = -1;

  public Cliente() {
    scanner = new Scanner(System.in);
    conectado = false;
    filaMensagens = new LinkedBlockingQueue<>();
    bufferLeitura = ByteBuffer.allocate(8192);
  }

  public void iniciar() {
    try {
      socketChannel = SocketChannel.open();
      socketChannel.configureBlocking(false);
      socketChannel.connect(new InetSocketAddress(ENDERECO_SERVIDOR, PORTA_SERVIDOR));

      // Aguardar conexão
      while (!socketChannel.finishConnect()) {
        Thread.sleep(100);
      }

      conectado = true;
      System.out.println("Chat Distribuído com Comunicação via Socket NIO");
      System.out.println("Conectado ao servidor!");

      // Login
      fazerLogin();

      // Iniciar thread para processar mensagens recebidas
      threadLeitura = new Thread(this::processarMensagens);
      threadLeitura.start();

      // Thread para ler dados do socket
      Thread threadSocket = new Thread(this::lerDoSocket);
      threadSocket.start();

      // Menu principal
      mostrarMenu();

    } catch (IOException | InterruptedException e) {
      System.err.println("Erro ao conectar ao servidor: " + e.getMessage());
    } finally {
      desconectar();
    }
  }

  private void lerDoSocket() {
    ByteBuffer buffer = ByteBuffer.allocate(8192);

    while (conectado && socketChannel.isOpen()) {
      try {
        buffer.clear();
        int bytesRead = socketChannel.read(buffer);

        if (bytesRead == -1) {
          // Servidor fechou a conexão
          conectado = false;
          break;
        } else if (bytesRead > 0) {
          buffer.flip();
          adicionarDados(buffer);

          // Tentar processar mensagens completas
          Mensagem mensagem;
          while ((mensagem = lerMensagem()) != null) {
            filaMensagens.offer(mensagem);
          }
        }

        Thread.sleep(10); // Pequena pausa para não sobrecarregar CPU

      } catch (IOException | InterruptedException e) {
        if (conectado) {
          System.err.println("Erro ao ler do socket: " + e.getMessage());
          conectado = false;
        }
        break;
      }
    }
  }

  private void adicionarDados(ByteBuffer novosDados) {
    // Expandir buffer se necessário
    if (bufferLeitura.remaining() < novosDados.remaining()) {
      int novaCapacidade = bufferLeitura.capacity() + novosDados.remaining();
      ByteBuffer novoBuffer = ByteBuffer.allocate(novaCapacidade);

      bufferLeitura.flip();
      novoBuffer.put(bufferLeitura);
      bufferLeitura = novoBuffer;
    }

    bufferLeitura.put(novosDados);
  }

  private Mensagem lerMensagem() {
    bufferLeitura.flip();

    try {
      // Se ainda não sabemos o tamanho da mensagem
      if (tamanhoMensagemEsperado == -1) {
        if (bufferLeitura.remaining() < 4) {
          bufferLeitura.compact();
          return null;
        }
        tamanhoMensagemEsperado = bufferLeitura.getInt();
      }

      // Verificar se temos a mensagem completa
      if (bufferLeitura.remaining() < tamanhoMensagemEsperado) {
        bufferLeitura.compact();
        return null;
      }

      // Ler a mensagem
      byte[] dadosMensagem = new byte[tamanhoMensagemEsperado];
      bufferLeitura.get(dadosMensagem);

      // Resetar para próxima mensagem
      tamanhoMensagemEsperado = -1;

      // Compactar buffer
      bufferLeitura.compact();

      // Deserializar mensagem
      try (ByteArrayInputStream bais = new ByteArrayInputStream(dadosMensagem);
          ObjectInputStream ois = new ObjectInputStream(bais)) {
        return (Mensagem) ois.readObject();
      }

    } catch (IOException | ClassNotFoundException e) {
      System.err.println("Erro ao deserializar mensagem: " + e.getMessage());
      bufferLeitura.compact();
      return null;
    }
  }

  private void processarMensagens() {
    while (conectado || !filaMensagens.isEmpty()) {
      try {
        Mensagem msg = filaMensagens.take();
        tratarMensagemRecebida(msg);
      } catch (InterruptedException e) {
        if (conectado) {
          System.err.println("Thread de processamento interrompida: " + e.getMessage());
        }
        break;
      }
    }
  }

  private void fazerLogin() {
    System.out.print("Digite seu nome de usuário: ");
    nomeUsuario = scanner.nextLine().trim();

    Mensagem loginMsg = new Mensagem(Mensagem.TipoMensagem.LOGIN, nomeUsuario);
    enviarMensagem(loginMsg);
  }

  private void tratarMensagemRecebida(Mensagem msg) {
    switch (msg.getTipo()) {
      case MENSAGEM_PRIVADA:
        System.out.println("\n" + msg.getRemetente() + " (privado): " + msg.getConteudo());
        break;
      case MENSAGEM_GRUPO:
        System.out.println("\n" + msg.getRemetente() + " (" + msg.getNomeGrupo() + "): " + msg.getConteudo());
        break;
      case TRANSFERENCIA_ARQUIVO:
        receberArquivo(msg);
        break;
      case SUCESSO:
        System.out.println("\n✓ " + msg.getConteudo());
        break;
      case ERRO:
        System.out.println("\n✗ " + msg.getConteudo());
        break;
      case STATUS:
        System.out.println("\nℹ " + msg.getConteudo());
        break;
    }

    if (conectado) {
      System.out.print("> ");
    }
  }

  private void receberArquivo(Mensagem msg) {
    try {
      String nomeArquivo = msg.getNomeArquivo();
      String caminhoArquivo = "downloads_cliente/" + nomeUsuario + "/" + nomeArquivo;
      Files.createDirectories(Paths.get("downloads_cliente/" + nomeUsuario));
      Files.write(Paths.get(caminhoArquivo), msg.getDadosArquivo());

      if (msg.getDestinatario() != null) {
        System.out.println("\n📎 Arquivo recebido de " + msg.getRemetente() + ": " + nomeArquivo + " (salvo em: " + caminhoArquivo + ")");
      } else {
        System.out.println("\n📎 Arquivo recebido de " + msg.getRemetente() + " no grupo " + msg.getNomeGrupo() + ": " + nomeArquivo + " (salvo em: " + caminhoArquivo + ")");
      }
    } catch (IOException e) {
      System.err.println("Erro ao salvar arquivo: " + e.getMessage());
    }
  }

  private void mostrarMenu() {
    System.out.println("\n=== MENU ===");
    System.out.println("1 = Enviar mensagem privada");
    System.out.println("2 = Enviar mensagem para grupo");
    System.out.println("3 = Enviar arquivo privado");
    System.out.println("4 = Enviar arquivo para grupo");
    System.out.println("5 = Criar grupo");
    System.out.println("6 = Entrar em grupo");
    System.out.println("0 = Sair");
    System.out.println("===============\n");

    while (conectado) {
      System.out.print("> ");
      String opcao = scanner.nextLine().trim();

      switch (opcao) {
        case "1": enviarMensagemPrivada(); break;
        case "2": enviarMensagemGrupo(); break;
        case "3": enviarArquivoPrivado(); break;
        case "4": enviarArquivoGrupo(); break;
        case "5": criarGrupo(); break;
        case "6": entrarGrupo(); break;
        case "0": sair(); return;
        case "menu":
        case "ajuda": mostrarMenu(); break;
        default:
          System.out.println("Opção inválida! Digite 'menu' para ver as opções novamente.");
      }
    }
  }

  private void enviarMensagemPrivada() {
    System.out.print("Destinatário: ");
    String destinatario = scanner.nextLine().trim();
    System.out.print("Mensagem: ");
    String conteudo = scanner.nextLine().trim();

    if (!destinatario.isEmpty() && !conteudo.isEmpty()) {
      Mensagem msg = new Mensagem(Mensagem.TipoMensagem.MENSAGEM_PRIVADA, nomeUsuario);
      msg.setDestinatario(destinatario);
      msg.setConteudo(conteudo);
      enviarMensagem(msg);
    }
  }

  private void enviarMensagemGrupo() {
    System.out.print("Nome do grupo: ");
    String nomeGrupo = scanner.nextLine().trim();
    System.out.print("Mensagem: ");
    String conteudo = scanner.nextLine().trim();

    if (!nomeGrupo.isEmpty() && !conteudo.isEmpty()) {
      Mensagem msg = new Mensagem(Mensagem.TipoMensagem.MENSAGEM_GRUPO, nomeUsuario);
      msg.setNomeGrupo(nomeGrupo);
      msg.setConteudo(conteudo);
      enviarMensagem(msg);
    }
  }

  private void enviarArquivoPrivado() {
    System.out.print("Destinatário: ");
    String destinatario = scanner.nextLine().trim();
    System.out.print("Caminho do arquivo: ");
    String caminho = scanner.nextLine().trim();
    if (!destinatario.isEmpty() && !caminho.isEmpty()) {
      enviarArquivo(destinatario, null, caminho);
    }
  }

  private void enviarArquivoGrupo() {
    System.out.print("Nome do grupo: ");
    String nomeGrupo = scanner.nextLine().trim();
    System.out.print("Caminho do arquivo: ");
    String caminho = scanner.nextLine().trim();
    if (!nomeGrupo.isEmpty() && !caminho.isEmpty()) {
      enviarArquivo(null, nomeGrupo, caminho);
    }
  }

  private void enviarArquivo(String destinatario, String nomeGrupo, String caminho) {
    try {
      Path path = Paths.get(caminho);

      if (!Files.exists(path)) {
        System.out.println("Arquivo não encontrado: " + caminho);
        return;
      }

      long tamanho = Files.size(path);
      if (tamanho > 50 * 1024 * 1024) {
        System.out.println("Arquivo muito grande (o tamanho máximo é 50MB)");
        return;
      }

      byte[] dados = Files.readAllBytes(path);
      String nomeArquivo = path.getFileName().toString();

      Mensagem msg = new Mensagem(Mensagem.TipoMensagem.TRANSFERENCIA_ARQUIVO, nomeUsuario);
      msg.setDestinatario(destinatario);
      msg.setNomeGrupo(nomeGrupo);
      msg.setNomeArquivo(nomeArquivo);
      msg.setDadosArquivo(dados);

      enviarMensagem(msg);

      System.out.println("Enviando arquivo: " + nomeArquivo);

    } catch (IOException e) {
      System.err.println("Erro ao processar arquivo: " + e.getMessage());
    }
  }

  private void criarGrupo() {
    System.out.print("Nome do grupo: ");
    String nomeGrupo = scanner.nextLine().trim();

    if (!nomeGrupo.isEmpty()) {
      Mensagem msg = new Mensagem(Mensagem.TipoMensagem.CRIAR_GRUPO, nomeUsuario);
      msg.setNomeGrupo(nomeGrupo);
      enviarMensagem(msg);
    }
  }

  private void entrarGrupo() {
    System.out.print("Nome do grupo: ");
    String nomeGrupo = scanner.nextLine().trim();

    if (!nomeGrupo.isEmpty()) {
      Mensagem msg = new Mensagem(Mensagem.TipoMensagem.ENTRAR_GRUPO, nomeUsuario);
      msg.setNomeGrupo(nomeGrupo);
      enviarMensagem(msg);
    }
  }

  private void sair() {
    Mensagem msg = new Mensagem(Mensagem.TipoMensagem.LOGOUT, nomeUsuario);
    enviarMensagem(msg);
    desconectar();
  }

  private void enviarMensagem(Mensagem msg) {
    try {
      if (!conectado || !socketChannel.isOpen()) {
        System.err.println("Não conectado ao servidor");
        return;
      }

      // Serializar mensagem
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      ObjectOutputStream oos = new ObjectOutputStream(baos);
      oos.writeObject(msg);
      oos.flush();

      byte[] dados = baos.toByteArray();
      ByteBuffer buffer = ByteBuffer.allocate(4 + dados.length);
      buffer.putInt(dados.length); // Tamanho da mensagem
      buffer.put(dados);
      buffer.flip();

      // Enviar dados
      while (buffer.hasRemaining()) {
        int bytesWritten = socketChannel.write(buffer);
        if (bytesWritten == 0) {
          Thread.sleep(10); // Aguardar socket estar pronto
        }
      }

    } catch (IOException | InterruptedException e) {
      System.err.println("Erro ao enviar mensagem: " + e.getMessage());
      conectado = false;
    }
  }

  private void desconectar() {
    if (conectado) {
      conectado = false;

      try {
        if (socketChannel != null && socketChannel.isOpen()) {
          socketChannel.close();
        }

        if (threadLeitura != null && threadLeitura.isAlive()) {
          threadLeitura.interrupt();
        }

      } catch (IOException e) {
        System.err.println("Erro ao fechar conexão: " + e.getMessage());
      }

      System.out.println("Desconectado do servidor.");
    }
  }

  public static void main(String[] args) {
    Cliente cliente = new Cliente();
    cliente.iniciar();
  }
}
