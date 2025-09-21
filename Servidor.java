import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class Servidor {
  private static final int PORTA = 8080;
  private ServerSocket servidorSocket;
  private Map<String, ClienteAtendimento> clientes;
  private Map<String, Grupo> grupos;
  private boolean executando;

  public Servidor() {
    clientes = new ConcurrentHashMap<>();
    grupos = new ConcurrentHashMap<>();
    executando = false;
  }

  public void iniciar() {
    try {
      servidorSocket = new ServerSocket(PORTA);
      executando = true;
      System.out.println("Servidor iniciado na porta " + PORTA);
      System.out.println("Aguardando conexoes\n");

      while (executando) {
        try {
          Socket clienteSocket = servidorSocket.accept();
          System.out.println("Nova conexao recebida de: " + clienteSocket.getInetAddress());

          ClienteAtendimento atendimento = new ClienteAtendimento(clienteSocket, this);
          atendimento.start();
        } catch (IOException e) {
          if (executando) {
            System.err.println("Erro ao aceitar conexao: " + e.getMessage());
          }
        }
      }
    } catch (IOException e) {
      System.err.println("Erro ao iniciar servidor: " + e.getMessage());
    }
  }

  public void parar() {
    executando = false;
    try {
      if (servidorSocket != null && !servidorSocket.isClosed()) {
        servidorSocket.close();
      }
    } catch (IOException e) {
      System.err.println("Erro ao parar servidor: " + e.getMessage());
    }
  }

  public synchronized boolean adicionarCliente(String usuario, ClienteAtendimento atendimento) {
    if (usuario == null || usuario.trim().isEmpty() || clientes.containsKey(usuario)) {
      return false;
    }

    clientes.put(usuario, atendimento);
    return true;
  }

  public synchronized void removerCliente(String usuario) {
    clientes.remove(usuario);

    // Remove usuario de todos os grupos
    for (Grupo grupo : grupos.values()) {
      grupo.removerMembro(usuario);
    }
  }

  public void enviarMensagemPrivada(String remetente, String destinatario, String conteudo) {
    ClienteAtendimento destinatarioAtendimento = clientes.get(destinatario);
    if (destinatarioAtendimento != null && destinatarioAtendimento.isConectado()) {
      Mensagem mensagem = new Mensagem(Mensagem.TipoMensagem.MENSAGEM_PRIVADA, remetente);
      mensagem.setConteudo(conteudo);
      mensagem.setDestinatario(destinatario);
      destinatarioAtendimento.enviarMensagem(mensagem);

      // Confirmar envio para o remetente
      ClienteAtendimento remetenteAtendimento = clientes.get(remetente);
      if (remetenteAtendimento != null) {
        Mensagem confirmacao = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
        confirmacao.setConteudo("Mensagem enviada para " + destinatario);
        remetenteAtendimento.enviarMensagem(confirmacao);
      }
    } else {
      // Usuario nao encontrado
      ClienteAtendimento remetenteAtendimento = clientes.get(remetente);
      if (remetenteAtendimento != null) {
        Mensagem erro = new Mensagem(Mensagem.TipoMensagem.ERRO);
        erro.setConteudo("Usuario nao encontrado ou offline: " + destinatario);
        erro.setSucesso(false);
        remetenteAtendimento.enviarMensagem(erro);
      }
    }
  }

  public void enviarMensagemGrupo(String remetente, String nomeGrupo, String conteudo) {
    Grupo grupo = grupos.get(nomeGrupo);
    if (grupo != null && grupo.eMembro(remetente)) {
      Mensagem mensagem = new Mensagem(Mensagem.TipoMensagem.MENSAGEM_GRUPO, remetente);
      mensagem.setConteudo(conteudo);
      mensagem.setNomeGrupo(nomeGrupo);

      // Enviar para todos os membros do grupo (exceto o remetente)
      for (String membro : grupo.getMembros()) {
        if (!membro.equals(remetente)) {
          ClienteAtendimento membroAtendimento = clientes.get(membro);
          if (membroAtendimento != null && membroAtendimento.isConectado()) {
            membroAtendimento.enviarMensagem(mensagem);
          }
        }
      }

      // Confirmar envio para o remetente
      ClienteAtendimento remetenteAtendimento = clientes.get(remetente);
      if (remetenteAtendimento != null) {
        Mensagem confirmacao = new Mensagem(Mensagem.TipoMensagem.SUCESSO);
        confirmacao.setConteudo("Mensagem enviada para o grupo " + nomeGrupo);
        remetenteAtendimento.enviarMensagem(confirmacao);
      }
    } else {
      ClienteAtendimento remetenteAtendimento = clientes.get(remetente);
      if (remetenteAtendimento != null) {
        Mensagem erro = new Mensagem(Mensagem.TipoMensagem.ERRO);
        erro.setConteudo("Grupo nao encontrado ou você nao e membro: " + nomeGrupo);
        erro.setSucesso(false);
        remetenteAtendimento.enviarMensagem(erro);
      }
    }
  }

  public void enviarArquivo(String remetente, String destinatario, String nomeArquivo, byte[] dadosArquivo) {
    ClienteAtendimento destinatarioAtendimento = clientes.get(destinatario);
    if (destinatarioAtendimento != null && destinatarioAtendimento.isConectado()) {
      Mensagem mensagem = new Mensagem(Mensagem.TipoMensagem.TRANSFERENCIA_ARQUIVO, remetente);
      mensagem.setDestinatario(destinatario);
      mensagem.setNomeArquivo(nomeArquivo);
      mensagem.setDadosArquivo(dadosArquivo);
      destinatarioAtendimento.enviarMensagem(mensagem);
    }
  }

  public void enviarArquivoParaGrupo(String remetente, String nomeGrupo, String nomeArquivo, byte[] dadosArquivo) {
    Grupo grupo = grupos.get(nomeGrupo);
    if (grupo != null && grupo.eMembro(remetente)) {
      Mensagem mensagem = new Mensagem(Mensagem.TipoMensagem.TRANSFERENCIA_ARQUIVO, remetente);
      mensagem.setNomeGrupo(nomeGrupo);
      mensagem.setNomeArquivo(nomeArquivo);
      mensagem.setDadosArquivo(dadosArquivo);

      // Enviar para todos os membros do grupo (exceto o remetente)
      for (String membro : grupo.getMembros()) {
        if (!membro.equals(remetente)) {
          ClienteAtendimento membroAtendimento = clientes.get(membro);
          if (membroAtendimento != null && membroAtendimento.isConectado()) {
            membroAtendimento.enviarMensagem(mensagem);
          }
        }
      }
    }
  }

  public synchronized boolean criarGrupo(String nomeGrupo) {
    if (nomeGrupo == null || nomeGrupo.trim().isEmpty() || grupos.containsKey(nomeGrupo)) {
      return false;
    }

    grupos.put(nomeGrupo, new Grupo(nomeGrupo));
    return true;
  }

  public synchronized boolean entrarGrupo(String usuario, String nomeGrupo) {
    Grupo grupo = grupos.get(nomeGrupo);
    return grupo != null && grupo.adicionarMembro(usuario);
  }

  public static void main(String[] args) {
    Servidor servidor = new Servidor();

    // Adicionar shutdown hook para parar o servidor
    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      System.out.println("\nParando servidor");
      servidor.parar();
    }));

    servidor.iniciar();
  }
}
