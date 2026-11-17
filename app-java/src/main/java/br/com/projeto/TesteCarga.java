package br.com.projeto;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

public class TesteCarga {

    // URLs dos 3 nós rodando no Docker
	private static final String[] NOS = {
		    "http://localhost:9081",
		    "http://localhost:9082",
		    "http://localhost:9083"
		};

    private static final HttpClient client = HttpClient.newHttpClient();

    public static void main(String[] args) throws Exception {
        System.out.println("=== INICIANDO EXPERIMENTOS DE CONCORRÊNCIA ===\n");

        // Experimento 1: SEM LOCK (Vai gerar inconsistência/estoque negativo)
        executarCenario("CENÁRIO 1: SEM LOCK DISTRIBUÍDO", false, 15, 5);

        Thread.sleep(2000); // Pausa entre testes

        // Experimento 2: COM LOCK (Consistência garantida)
        executarCenario("CENÁRIO 2: COM REDLOCK (REDIS)", true, 15, 5);
    }

    private static void executarCenario(String titulo, boolean usarLock, int totalRequisicoes, int estoqueInicial) throws Exception {
        System.out.println("--------------------------------------------------");
        System.out.println(titulo);
        
        // 1. Reseta o estoque antes do teste
        enviarPost(NOS[0] + "/reset?qtd=" + estoqueInicial);
        System.out.println("Estoque inicial configurado para: " + estoqueInicial);
        System.out.println("Disparando " + totalRequisicoes + " compras simultâneas nos 3 nós...\n");

        // Pool de threads para simular usuários simultâneos
        ExecutorService executor = Executors.newFixedThreadPool(totalRequisicoes);
        List<Callable<ResultadoReq>> tarefas = new ArrayList<>();

        for (int i = 0; i < totalRequisicoes; i++) {
            // Distribui as requisições em round-robin entre os 3 nós (8081, 8082, 8083)
            String urlNo = NOS[i % NOS.length] + "/comprar?usarLock=" + usarLock;
            
            tarefas.add(() -> {
                long inicio = System.nanoTime();
                HttpResponse<String> resp = enviarPost(urlNo);
                long fim = System.nanoTime();
                double latenciaMs = (fim - inicio) / 1_000_000.0;
                return new ResultadoReq(resp.statusCode(), resp.body(), latenciaMs);
            });
        }

        long inicioTeste = System.nanoTime();
        // invokeAll dispara todas as requisições exatamente ao mesmo tempo
        List<Future<ResultadoReq>> futuros = executor.invokeAll(tarefas);
        long fimTeste = System.nanoTime();
        executor.shutdown();

        // 2. Coleta e calcula métricas para o seu Relatório Técnico
        int sucessos = 0;
        int bloqueadosEstoque = 0;
        int bloqueadosTimeout = 0;
        double somaLatencia = 0;

        for (Future<ResultadoReq> f : futuros) {
            ResultadoReq res = f.get();
            somaLatencia += res.latenciaMs;
            if (res.status == 200) sucessos++;
            else if (res.status == 409) bloqueadosEstoque++;
            else if (res.status == 429) bloqueadosTimeout++;
        }

        double tempoTotalSeg = (fimTeste - inicioTeste) / 1_000_000_000.0;
        double vazao = totalRequisicoes / tempoTotalSeg;
        double latenciaMedia = somaLatencia / totalRequisicoes;

        // Consulta como ficou o estoque final no Redis
        String estoqueFinal = enviarGet(NOS[0] + "/estoque");

        // 3. Imprime os resultados prontos para copiar para o relatório
        System.out.println(">>> RESULTADOS DO EXPERIMENTO <<<");
        System.out.printf("Vendas aprovadas (HTTP 200): %d (Esperado: %d)\n", sucessos, estoqueInicial);
        System.out.printf("Barrados por falta de estoque (HTTP 409): %d\n", bloqueadosEstoque);
        System.out.printf("Barrados por timeout do lock (HTTP 429): %d\n", bloqueadosTimeout);
        System.out.println("Estado no Banco: " + estoqueFinal);
        System.out.printf("Tempo total: %.2f s | Vazão: %.2f req/s | Latência média: %.2f ms\n", tempoTotalSeg, vazao, latenciaMedia);
        System.out.println("--------------------------------------------------\n");
    }

    private static HttpResponse<String> enviarPost(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).POST(HttpRequest.BodyPublishers.noBody()).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String enviarGet(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).body();
    }

    // Classe auxiliar para guardar os dados de cada requisição
    static class ResultadoReq {
        final int status;
        final String body;
        final double latenciaMs;

        ResultadoReq(int status, String body, double latenciaMs) {
            this.status = status;
            this.body = body;
            this.latenciaMs = latenciaMs;
        }
    }
}