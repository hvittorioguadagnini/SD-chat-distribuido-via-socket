import java.io.Serializable;

public class Mensagem implements Serializable {

  private static final long serialVersionUID = 1L;

  public enum TipoMensagem {
    LOGIN, LOGOUT, MENSAGEM_PRIVADA, MENSAGEM_GRUPO, TRANSFERENCIA_ARQUIVO,
    CRIAR_GRUPO, ENTRAR_GRUPO, STATUS, SUCESSO, ERRO
  }

  private TipoMensagem tipo;
  private String remetente;
  private String destinatario;
  private String conteudo;
  private String nomeGrupo;
  private byte[] dadosArquivo;
  private String nomeArquivo;
  private boolean sucesso;

  public Mensagem(TipoMensagem tipo, String remetente) {
    this.tipo = tipo;
    this.remetente = remetente;
    this.sucesso = true;
  }

  public Mensagem(TipoMensagem tipo) {
    this(tipo, "SERVIDOR");
  }

  public TipoMensagem getTipo() {
    return tipo;
  }

  public String getRemetente() {
    return remetente;
  }

  public String getDestinatario() {
    return destinatario;
  }

  public void setDestinatario(String destinatario) {
    this.destinatario = destinatario;
  }

  public String getConteudo() {
    return conteudo;
  }

  public void setConteudo(String conteudo) {
    this.conteudo = conteudo;
  }

  public String getNomeGrupo() {
    return nomeGrupo;
  }

  public void setNomeGrupo(String nomeGrupo) {
    this.nomeGrupo = nomeGrupo;
  }

  public byte[] getDadosArquivo() {
    return dadosArquivo;
  }

  public void setDadosArquivo(byte[] dadosArquivo) {
    this.dadosArquivo = dadosArquivo;
  }

  public String getNomeArquivo() {
    return nomeArquivo;
  }

  public void setNomeArquivo(String nomeArquivo) {
    this.nomeArquivo = nomeArquivo;
  }

  public void setSucesso(boolean sucesso) {
    this.sucesso = sucesso;
  }
}
