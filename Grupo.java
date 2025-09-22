import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

// Classe para representar um grupo de chat.
public class Grupo {
  private String nome;
  private Set<String> membros;

  // Construtor da classe Grupo.
  public Grupo(String nome) {
    this.nome = nome;
    this.membros = ConcurrentHashMap.newKeySet();
  }

  // Adiciona um membro ao grupo.
  public synchronized boolean adicionarMembro(String usuario) {
    return membros.add(usuario);
  }

  // Remove um membro do grupo.
  public synchronized boolean removerMembro(String usuario) {
    return membros.remove(usuario);
  }

  // Verifica se um usuário é membro do grupo.
  public synchronized boolean eMembro(String usuario) {
    return membros.contains(usuario);
  }

  // Getters
  public synchronized Set<String> getMembros() {
    return new HashSet<>(membros);
  }

  public String getNome() {
    return nome;
  }

  public synchronized int getTamanho() {
    return membros.size();
  }
}

