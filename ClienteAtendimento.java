import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;

public class ClienteAtendimento extends Thread {
  private Socket socket;
  private ObjectInputStream entrada;
  private ObjectOutputStream saida;
  private String nomeUsuario;
  private Servidor servidor;
  private boolean conectado;

  public ClienteAtendimento(Socket socket, Servidor servidor) {
    this.socket = socket;
    this.servidor = servidor;
    this.conectado = true;

    try {
      this.saida = new ObjectOutputStream(socket.getOutputStream());
      this.entrada = new ObjectInputStream(socket.getInputStream());
    } catch (IOException e) {
      System.err.println("Erro ao inicializar streams: " + e.getMessage());
    }
  }

  @Override
  public void run() {
    try {
      Mensagem mensagem;
      while (conectado && (mensagem = (Mensagem) entrada.readObject()) != null) {
        processarMensagem(mensagem);
      }
    } catch (IOException | ClassNotFoundException e) {
      if (conectado) {
        System.err.println("Erro na conexao com cliente: " + e.getMessage());
      }
    } finally {
      desconectar();
    }
  }

  private void processarMensagem(Mensagem mensagem) {
    switch (mensagem.getTipo()) {
      case LOGIN: login(mensagem); break;
      case LOGOUT: logout(); break;
      case MENSAGEM_PRIVADA: mensagemPrivada(mensagem); break;
      case MENSAGEM_GRUPO: mensagemGrupo(mensagem); break;
      case TRANSFERENCIA_ARQUIVO: transferenciaArquivo(mensagem); break;
      case CRIAR_GRUPO: criarGrupo(mensagem); break;
      case ENTRAR_GRUPO: entrarGrupo(mensagem); break;
    }
  }

  private void login(Mensagem mensagem) {
    String usuarioSolicitado = mensagem.getRemetente();
    if (servidor.adicionarCliente(usuarioSolicitado, this)) {
      this.nomeUsuario = usuarioSolicitado;
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Login realizado com sucesso como: " + usuarioSolicitado);
      enviarMensagem(resposta);
      System.out.println("Cliente conectado: " + nomeUsuario);
    } else {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Nome de usuario ja está sendo usado.");
      resposta.setSucesso(false);
      enviarMensagem(resposta);
      desconectar();
    }
  }

  private void logout() {
    desconectar();
  }

  private void mensagemPrivada(Mensagem mensagem) {
    servidor.enviarMensagemPrivada(mensagem.getRemetente(), mensagem.getDestinatario(), mensagem.getConteudo());
  }

  private void mensagemGrupo(Mensagem mensagem) {
    servidor.enviarMensagemGrupo(mensagem.getRemetente(), mensagem.getNomeGrupo(), mensagem.getConteudo());
  }

  private void transferenciaArquivo(Mensagem mensagem) {
    try {
      String nomeArquivo = mensagem.getNomeArquivo();
      String caminhoArquivo = "arquivos_servidor/" + nomeArquivo;
      Files.createDirectories(Paths.get("arquivos_servidor"));
      Files.write(Paths.get(caminhoArquivo), mensagem.getDadosArquivo());

      if (mensagem.getDestinatario() != null) {
        servidor.enviarArquivo(mensagem.getRemetente(), mensagem.getDestinatario(), nomeArquivo, mensagem.getDadosArquivo());
      } else if (mensagem.getNomeGrupo() != null) {
        servidor.enviarArquivoParaGrupo(mensagem.getRemetente(), mensagem.getNomeGrupo(), nomeArquivo, mensagem.getDadosArquivo());
      }

      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Arquivo enviado com sucesso: " + nomeArquivo);
      enviarMensagem(resposta);

    } catch (IOException e) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Falha ao processar arquivo - " + e.getMessage());
      resposta.setSucesso(false);
      enviarMensagem(resposta);
    }
  }

  private void criarGrupo(Mensagem mensagem) {
    String nomeGrupo = mensagem.getNomeGrupo();
    if (servidor.criarGrupo(nomeGrupo)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Grupo criado com sucesso: " + nomeGrupo);
      enviarMensagem(resposta);
    } else {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Grupo já existe ou nome inválido: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta);
    }
  }

  private void entrarGrupo(Mensagem mensagem) {
    String nomeGrupo = mensagem.getNomeGrupo();
    if (servidor.entrarGrupo(nomeUsuario, nomeGrupo)) {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
      resposta.setConteudo("Voce entrou no grupo: " + nomeGrupo);
      enviarMensagem(resposta);
    } else {
      Mensagem resposta = new Mensagem(Mensagem.TipoMensagem.ERRO);
      resposta.setConteudo("ERRO: Nao foi possível entrar no grupo: " + nomeGrupo);
      resposta.setSucesso(false);
      enviarMensagem(resposta);
    }
  }

  public void enviarMensagem(Mensagem mensagem) {
    try {
      if (conectado && saida != null) {
        synchronized(saida) {
          saida.writeObject(mensagem);
          saida.flush();
        }
      }
    } catch (IOException e) {
      System.err.println("Erro ao enviar mensagem para " + nomeUsuario + ": " + e.getMessage());
      desconectar();
    }
  }

  private void desconectar() {
    if (conectado) {
      conectado = false;
      if (nomeUsuario != null) {
        servidor.removerCliente(nomeUsuario);
        System.out.println("Cliente desconectado: " + nomeUsuario);
      }
      try {
        if (entrada != null) entrada.close();
        if (saida != null) saida.close();
        if (socket != null) socket.close();
      } catch (IOException e) {
        System.err.println("Erro ao fechar conexao: " + e.getMessage());
      }
    }
  }

  public String getNomeUsuario() { return nomeUsuario; }
  public boolean isConectado() { return conectado; }
}
