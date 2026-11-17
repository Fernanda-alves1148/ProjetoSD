# ProjetoSD - Simulador de Compra de Ingressos

# Controle de Concorrência Distribuída com Java, Redis e Docker

Simulador distribuído de venda de ingressos desenvolvido para demonstrar, na prática, os desafios de exclusão mútua, condições de corrida (*race conditions*) e tolerância a falhas em sistemas concorrentes. O projeto compara a execução de requisições simultâneas sem controle de acesso, com travas distribuídas via **Redisson/Redis** e sob queda abrupta de nós com recuperação automática por tempo de expiração (**TTL**).

**Autor(a):** Fernanda Alves 
**Disciplina:** Sistemas Distribuídos  

---

## Objetivo

Avaliar o compromisso (*trade-off*) entre **consistência** e **desempenho** em um ambiente distribuído composto por múltiplos nós independentes disputando um recurso compartilhado (estoque limitado de ingressos), comprovando:
1. A ocorrência de vendas duplicadas (*overselling*) na ausência de coordenação distribuída;
2. A garantia de consistência exata do estoque com uso de travas distribuídas (`RLock`);
3. A prevenção de *deadlocks* e a recuperação automática da disponibilidade após falha catastrófica de um nó por meio de *leases* (TTL).

---

## Tecnologias e Bibliotecas Utilizadas

* **Java 8 (JDK 1.8+):** Linguagem base da aplicação servidora e do cliente de testes.
* **Apache Maven:** Gerenciamento de dependências e empacotamento do projeto.
* **Javalin:** Microframework web leve utilizado para expor os endpoints REST de compra e simulação de falha em cada nó.
* **Redisson:** Cliente Redis para Java responsável pela implementação das travas distribuídas (`RLock` / protocolo Redlock) e comunicação com o coordenador em memória.
* **Redis (`redis:alpine`):** Armazenamento em memória utilizado para manter o estado compartilhado do estoque e coordenar o bloqueio entre os nós.
* **Docker & Docker Compose:** Conteinerização e orquestração da rede isolada entre o servidor Redis e as três instâncias da aplicação Java.

---

## Estrutura e Configuração do Ambiente Docker

O arquivo `docker-compose.yml` inicializa quatro contêineres conectados em uma rede interna dedicada:

| Serviço | Descrição | Porta Local | Variáveis de Ambiente |
| :--- | :--- | :---: | :--- |
| **`redis-server`** | Instância central do Redis (`redis:alpine`) | `6379` | — |
| **`app-node-1`** | Nó trabalhador 1 da aplicação Java | `9081` | `REDIS_URL=redis://redis-server:6379`, `NODE_NAME=NÓ-1` |
| **`app-node-2`** | Nó trabalhador 2 da aplicação Java | `9082` | `REDIS_URL=redis://redis-server:6379`, `NODE_NAME=NÓ-2` |
| **`app-node-3`** | Nó trabalhador 3 da aplicação Java | `9083` | `REDIS_URL=redis://redis-server:6379`, `NODE_NAME=NÓ-3` |

### Pré-requisitos
* **Docker Desktop** instalado e em execução (no Windows, recomenda-se a integração com **WSL 2** habilitada).
* **JDK 8+** e **Maven** (ou IDE como Eclipse) caso deseje executar a classe cliente `Testar.java` localmente fora dos contêineres.

---

## Como Executar o Projeto

### 1. Subir os contêineres com `docker compose up`
Na pasta raiz do projeto (onde está localizado o arquivo `docker-compose.yml`), execute:

```bash
docker compose up --build
```

*(Dica: caso queira liberar o terminal após iniciar os serviços, utilize `docker compose up --build -d` para rodar em segundo plano e acompanhe a saída com `docker compose logs -f`.)*

Para confirmar que o Redis e os três nós Java estão ativos, abra outro terminal e verifique:

```bash
docker ps
```

### 2. Executar os testes de carga (Experimentos 1 e 2)
Com os contêineres rodando nas portas `9081`, `9082` e `9083`, execute a classe **`Testar.java`** (via Eclipse ou linha de comando Maven) para disparar 15 requisições concorrentes disputando um estoque de 5 ingressos:

```bash
cd app-java
mvn test-compile exec:java -Dexec.mainClass="Testar"
```

### 3. Consultar o estoque no Redis e encerrar o ambiente
Para verificar diretamente no Redis o saldo restante após os testes:

```bash
docker compose exec redis-server redis-cli KEYS "*"
docker compose exec redis-server redis-cli GET NOME_DA_CHAVE
```

Para desligar e remover os contêineres ao finalizar:

```bash
docker compose down
```

---

## Passo a Passo: Simulação da Condição de Falha (Experimento 3)

O endpoint `POST /simular-crash` faz com que o nó alvo adquira a trava distribuída `ingresso_unico_lock` com tempo de espera de **2 segundos** e validade (**TTL**) de **5 segundos**, encerrando a JVM imediatamente (`System.exit(1)`) antes que o bloco `finally` possa liberar o bloqueio.

Para reproduzir o cenário de queda e recuperação automática:

1. **Inicie o ambiente e abra os logs em tempo real (Terminal 1):**
   ```bash
   docker compose up -d
   docker compose logs -f -t
   ```

2. **Provoque a queda abrupta do `NÓ-1` (Terminal 2):**
   Envie uma requisição `POST` para a rota de falha na porta `9081`:
   ```bash
   curl -X POST http://localhost:9081/simular-crash
   ```
   *Comportamento esperado:* O `NÓ-1` exibirá no log `"ATENÇÃO: Lock adquirido! Simulando falha catastrófica (CRASH) agora..."` e encerrará seu contêiner mantendo a trava presa no Redis.

3. **Teste a contenção durante o TTL no `NÓ-2` (dentro de 5 segundos):**
   Imediatamente após derrubar o `NÓ-1`, envie uma requisição de compra para o `NÓ-2`:
   ```bash
   curl -X POST http://localhost:9082/comprar
   ```
   *Comportamento esperado:* Como a trava ainda pertence ao `NÓ-1` caído, o `NÓ-2` aguarda o limite configurado (`waitTime` de 2s) e retorna tempo esgotado, impedindo venda duplicada.

4. **Valide a recuperação automática após expiração do TTL (após 5 segundos):**
   Passados os 5 segundos do TTL, o Redis remove a trava órfã automaticamente. Envie uma nova requisição para o `NÓ-3`:
   ```bash
   curl -X POST http://localhost:9083/comprar
   ```
   *Comportamento esperado:* O `NÓ-3` adquire a trava normalmente e conclui a operação sem necessidade de intervenção manual no cluster.

5. **Restaure o `NÓ-1` ao final do experimento:**
   ```bash
   docker compose up -d
   ```

---

## Resultados Obtidos

Os testes foram conduzidos com **15 requisições simultâneas** disputando um estoque configurado para **5 ingressos**:

| Métricas | Experimento 1 (Sem Lock) | Experimento 2 (Com Redisson) |
| :--- | :---: | :---: |
| **Vendas Aprovadas** | 15 | 5 |
| **Requisições Barradas** | 0 | 10 |
| **Timeouts** | 0 | 0 |
| **Tempo de Execução** | 0,69 s | 1,85 s |
| **Throughput** | 21,71 req/s | 8,09 req/s |
| **Latência Média** | 578,51 ms | 1542,50 ms |

* **Experimento 1:** Apresentou alta vazão (`21,71 req/s`), mas violou a integridade do negócio ao aprovar 15 vendas para apenas 5 ingressos disponíveis.
* **Experimento 2:** Garantiu consistência total (`5` vendas aprovadas e `10` barradas), com redução de ~63% no *throughput* e aumento de ~2,7x na latência devido à serialização dos nós na seção crítica.
* **Experimento 3:** Confirmou a tolerância a falhas do sistema, mantendo o bloqueio seguro durante a janela de `5 s` do TTL e restabelecendo a disponibilidade automaticamente após a expiração da trava.
