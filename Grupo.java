import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Grupo {
  private String nome;
  private Set<String> membros;

  public Grupo(String nome) {
    this.nome = nome;
    this.membros = ConcurrentHashMap.newKeySet();
  }

  public synchronized boolean adicionarMembro(String usuario) {
    return membros.add(usuario);
  }

  public synchronized boolean removerMembro(String usuario) {
    return membros.remove(usuario);
  }

  public synchronized boolean eMembro(String usuario) {
    return membros.contains(usuario);
  }

  public synchronized Set<String> getMembros() {
    return new HashSet<>(membros);
  }
}
