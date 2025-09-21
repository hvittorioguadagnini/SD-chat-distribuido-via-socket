import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;

public class Cliente {
  private static final String ENDERECO_SERVIDOR = "ec2-3-17-128-71.us-east-2.compute.amazonaws.com";
  private static final int PORTA_SERVIDOR = 8080;

  private Socket socket;
  private ObjectOutputStream saida;
  private ObjectInputStream entrada;
  private String nomeUsuario;
  private boolean conectado;
  private Scanner scanner;

  public Cliente() {
    scanner = new Scanner(System.in);
    conectado = false;
  }

  public void iniciar() {
    try {
      socket = new Socket(ENDERECO_SERVIDOR, PORTA_SERVIDOR);
      saida = new ObjectOutputStream(socket.getOutputStream());
      entrada = new ObjectInputStream(socket.getInputStream());
      conectado = true;

      System.out.println("Chat Distribuído com Comunicacao via Socket");
      System.out.println("Conectado ao servidor!");

      // Login
      fazerLogin();

      // Iniciar thread para receber mensagens
      Thread ouvinteMensagens = new Thread(this::escutarMensagens);
      ouvinteMensagens.start();

      // Menu principal
      mostrarMenu();

    } catch (IOException e) {
      System.err.println("Erro ao conectar ao servidor: " + e.getMessage());
    } finally {
      desconectar();
    }
  }

  private void fazerLogin() {
    System.out.print("Digite seu nome de usuario: ");
    nomeUsuario = scanner.nextLine().trim();

    Mensagem loginMsg = new Mensagem(Mensagem.TipoMensagem.LOGIN, nomeUsuario);
    enviarMensagem(loginMsg);
  }

  private void escutarMensagens() {
    try {
      Mensagem msg;
      while (conectado && (msg = (Mensagem) entrada.readObject()) != null) {
        tratarMensagemRecebida(msg);
      }
    } catch (IOException | ClassNotFoundException e) {
      if (conectado) {
        System.err.println("Conexao com servidor perdida: " + e.getMessage());
        conectado = false;
      }
    }
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
      case ERRO:
      case STATUS:
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
        System.out.println("\nArquivo recebido de " + msg.getRemetente() + ": " + nomeArquivo + " (salvo em: " + caminhoArquivo + ")");
      } else {
        System.out.println("\nArquivo recebido de " + msg.getRemetente() + " no grupo " + msg.getNomeGrupo() + ": " + nomeArquivo + " (salvo em: " + caminhoArquivo + ")");
      }
    } catch (IOException e) {
      System.err.println("Erro ao salvar arquivo: " + e.getMessage());
    }
  }

  private void mostrarMenu() {
    System.out.println("\nMENU");
    System.out.println("1 = Enviar mensagem privada");
    System.out.println("2 = Enviar mensagem para grupo");
    System.out.println("3 = Enviar arquivo privado");
    System.out.println("4 = Enviar arquivo para grupo");
    System.out.println("5 = Criar grupo");
    System.out.println("6 = Entrar em grupo");
    System.out.println("0 = Sair");
    System.out.println("============================\n");

    while (conectado) {
      System.out.print("");
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
          System.out.println("Opcao invalida! Digite 'menu' para ver as opcoes novamente.");
      }
    }
  }

  private void enviarMensagemPrivada() {
    System.out.print("Destinatario: ");
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
    System.out.print("Destinatario: ");
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
        System.out.println("Arquivo nao encontrado: " + caminho);
        return;
      }

      long tamanho = Files.size(path);
      if (tamanho > 50 * 1024 * 1024) {
        System.out.println("Arquivo muito grande (o tamanho maximo é 50MB)");
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
      if (conectado && saida != null) {
        synchronized(saida) {
          saida.writeObject(msg);
          saida.flush();
        }
      }
    } catch (IOException e) {
      System.err.println("Erro ao enviar mensagem: " + e.getMessage());
      conectado = false;
    }
  }

  private void desconectar() {
    if (conectado) {
      conectado = false;
      try {
        if (saida != null) saida.close();
        if (entrada != null) entrada.close();
        if (socket != null) socket.close();
      } catch (IOException e) {
        System.err.println("Erro ao fechar conexao: " + e.getMessage());
      }
      System.out.println("Desconectado do servidor.");
    }
  }

  public static void main(String[] args) {
    Cliente cliente = new Cliente();
    cliente.iniciar();
  }
}
