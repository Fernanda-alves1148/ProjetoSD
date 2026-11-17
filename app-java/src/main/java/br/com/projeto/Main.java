package br.com.projeto;

import io.javalin.Javalin;
import org.redisson.Redisson;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {
        String redisUrl = System.getenv().getOrDefault("REDIS_URL", "redis://127.0.0.1:6379");
        String nodeName = System.getenv().getOrDefault("NODE_NAME", "Nó-Desconhecido");

        Config config = new Config();
        config.useSingleServer().setAddress(redisUrl);
        RedissonClient redisson = Redisson.create(config);

        // Recurso compartilhado no Redis: Estoque inicial de 5 ingressos
        RAtomicLong estoque = redisson.getAtomicLong("estoque_ingressos");
        estoque.set(5);

        Javalin app = Javalin.create().start(8080);

        // 1. Endpoint para resetar o estoque antes de cada teste
        app.post("/reset", ctx -> {
            int qtd = Integer.parseInt(ctx.queryParamAsClass("qtd", String.class).getOrDefault("5"));
            estoque.set(qtd);
            System.out.println("[" + nodeName + "] Estoque resetado para: " + qtd);
            ctx.status(200).result("Estoque resetado para " + qtd);
        });

        // 2. Endpoint para consultar estoque atual
        app.get("/estoque", ctx -> {
            ctx.status(200).result("Estoque atual: " + estoque.get());
        });

        // 3. Endpoint de compra (Suporta ?usarLock=true ou ?usarLock=false)
        app.post("/comprar", ctx -> {
            boolean usarLock = Boolean.parseBoolean(ctx.queryParamAsClass("usarLock", String.class).getOrDefault("true"));
            RLock lock = redisson.getLock("ingresso_unico_lock");
            boolean adquiriuLock = false;

            try {
                if (usarLock) {
                    // Espera até 3s pelo lock. Se pegar, segura por no máximo 5s (TTL)
                    adquiriuLock = lock.tryLock(3, 5, TimeUnit.SECONDS);
                    if (!adquiriuLock) {
                        System.out.println("[" + nodeName + "] Timeout: Sistema congestionado.");
                        ctx.status(429).result("[" + nodeName + "] Erro 429: Muitas requisições simultâneas.");
                        return;
                    }
                }

                // --- INÍCIO DA REGIÃO CRÍTICA ---
                long atual = estoque.get();
                if (atual > 0) {
                    Thread.sleep(300); // Simula processamento
                    // Troque decrementAndGet() por set(atual - 1) para simular um banco SQL comum (SELECT seguido de UPDATE)
                    long novoEstoque = atual - 1;
                    estoque.set(novoEstoque); 
                    System.out.println("[" + nodeName + "] SUCESSO! Ingresso vendido. Restam: " + novoEstoque);
                    ctx.status(200).result("[" + nodeName + "] Ingresso comprado! Restam: " + novoEstoque);
                } else {
                    System.out.println("[" + nodeName + "] ESGOTADO! Tentativa bloqueada.");
                    ctx.status(409).result("[" + nodeName + "] Erro 409: Ingressos esgotados!");
                }
                // --- FIM DA REGIÃO CRÍTICA ---

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                ctx.status(500).result("Erro interno.");
            } finally {
                if (usarLock && adquiriuLock && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        });

        // 4. Endpoint de Simulação de Falha (Pega o lock e derruba o container antes de liberar)
        app.post("/simular-crash", ctx -> {
            RLock lock = redisson.getLock("ingresso_unico_lock");
            if (lock.tryLock(2, 5, TimeUnit.SECONDS)) {
                System.out.println("[" + nodeName + "] ATENÇÃO: Lock adquirido! Simulando falha catastrófica (CRASH) agora...");
                // Encerra a JVM abruptamente sem executar o finally/unlock!
                System.exit(1); 
            } else {
                ctx.status(409).result("Não foi possível pegar o lock para simular crash.");
            }
        });
    }
}