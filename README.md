# Chat Distribuído com Sockets

Este é um projeto da disciplina de Sistemas Distribuídos que implementa um sistema de chat distribuído no modelo cliente-servidor, semelhante a aplicativos de mensagens (como WhatsApp), utilizando **Java NIO (non-blocking I/O)** para suportar múltiplos clientes.

---

## Funcionalidades
- Login, logout, envio de mensagens e arquivos privados e em grupo.  

---

## Estrutura de Classes

### `Cliente`
Classe principal do cliente que conecta ao servidor, tem o menu e gerencia os envios.    

### `ClienteService`
Classe usada pelo servidor para representar um cliente conectado através do SocketChannel e com buffers de leitura/escrita.  

### `Servidor`
Classe principal do servidor que aceita novas conexões e é responsável por processar e encaminhar mensagens e arquivos.  

### `Grupo`
Representa um grupo de chat.   

### `Mensagem`
Objeto serializável trocado entre cliente e servidor.  

---

## Compilar

### 1. Compilar todas as classes
Na pasta onde estão os `.java`:
```bash
javac *.java
````

### 2. Iniciar o servidor

```bash
java Servidor
```

### 3. Abrir um ou mais clientes

Cada cliente em um terminal separado:

```bash
java Cliente
```
