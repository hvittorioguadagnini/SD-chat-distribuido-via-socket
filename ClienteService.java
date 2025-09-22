import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.LinkedList;
import java.util.Queue;

// Classe para armazenar informações do cliente.
public class ClienteService {
  private SocketChannel channel;
  private String nomeUsuario;
  private boolean conectado;
  private ByteBuffer bufferLeitura;
  private Queue<ByteBuffer> filaEscrita;
  private ByteBuffer bufferEscritaAtual;
  private int tamanhoMensagemEsperado = -1;

  // Construtor da classe ClienteInfo.
  public ClienteService(SocketChannel channel) {
    this.channel = channel;
    this.conectado = true;
    this.bufferLeitura = ByteBuffer.allocate(8192);
    this.filaEscrita = new LinkedList<>();
  }

  // Adiciona dados recebidos ao buffer de leitura.
  public void adicionarDados(ByteBuffer novosDados) {
    if (bufferLeitura.remaining() < novosDados.remaining()) {
      int novaCapacidade = bufferLeitura.capacity() + novosDados.remaining();
      ByteBuffer novoBuffer = ByteBuffer.allocate(novaCapacidade);

      bufferLeitura.flip();
      novoBuffer.put(bufferLeitura);
      bufferLeitura = novoBuffer;
    }

    bufferLeitura.put(novosDados);
  }

  // Lê uma mensagem completa do buffer de leitura, se disponível.
  public Mensagem lerMensagem() {
    bufferLeitura.flip();

    try {
      // Se ainda não sabemos o tamanho da mensagem.
      if (tamanhoMensagemEsperado == -1) {
        if (bufferLeitura.remaining() < 4) {
          // Não temos bytes suficientes para ler o tamanho.
          bufferLeitura.compact();
          return null;
        }
        tamanhoMensagemEsperado = bufferLeitura.getInt();
      }

      // Verificar se temos a mensagem completa.
      if (bufferLeitura.remaining() < tamanhoMensagemEsperado) {
        // Mensagem incompleta
        bufferLeitura.compact();
        return null;
      }

      // Ler a mensagem.
      byte[] dadosMensagem = new byte[tamanhoMensagemEsperado];
      bufferLeitura.get(dadosMensagem);

      // Resetar para próxima mensagem.
      tamanhoMensagemEsperado = -1;

      // Compactar buffer para proximas leituras.
      bufferLeitura.compact();

      // Deserializar mensagem.
      try (ByteArrayInputStream bais = new ByteArrayInputStream(dadosMensagem);
          ObjectInputStream ois = new ObjectInputStream(bais)) {
        return (Mensagem) ois.readObject();
      }

    } catch (IOException | ClassNotFoundException e) {
      System.err.println("Erro ao desserializar mensagem: " + e.getMessage());
      bufferLeitura.compact();
      return null;
    }
  }

  // Adiciona um buffer de escrita à fila.
  public void adicionarParaEscrita(ByteBuffer buffer) {
    synchronized (filaEscrita) {
      filaEscrita.offer(buffer);
    }
  }

  // Obtém o próximo buffer de escrita, se disponível.
  public ByteBuffer getBufferEscrita() {
    synchronized (filaEscrita) {
      if (bufferEscritaAtual == null || !bufferEscritaAtual.hasRemaining()) {
        bufferEscritaAtual = filaEscrita.poll();
      }
      return bufferEscritaAtual;
    }
  }

  // Limpa o buffer de escrita atual após o envio completo.
  public void limparBufferEscrita() {
    synchronized (filaEscrita) {
      bufferEscritaAtual = null;
    }
  }

  // Fecha a conexão do cliente.
  public void fechar() {
    conectado = false;
    try {
      if (channel != null) {
        channel.close();
      }
    } catch (IOException e) {
      System.err.println("Erro ao fechar canal do cliente: " + e.getMessage());
    }
  }

  // Getters e Setters
  public SocketChannel getChannel() {
    return channel;
  }

  public String getNomeUsuario() {
    return nomeUsuario;
  }

  public void setNomeUsuario(String nomeUsuario) {
    this.nomeUsuario = nomeUsuario;
  }

  public boolean isConectado() {
    return conectado && channel.isOpen();
  }
}

