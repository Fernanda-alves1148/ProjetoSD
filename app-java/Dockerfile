FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
# Copia o jar gordo gerado pelo Maven para dentro do container
COPY target/app-java-0.0.1-SNAPSHOT-jar-with-dependencies.jar /app/app.jar
# Expõe a porta que o Javalin está rodando
EXPOSE 8080
# Comando para rodar a aplicação
CMD ["java", "-jar", "app.jar"]