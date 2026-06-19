package dev.cerez.tahp.io;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@UtilityClass
public class IOdata {

    public record ApiKeysBinance(String key, String secret){}

    @SneakyThrows
    public ApiKeysBinance loadApiKeysBinance() {
        Path path = Paths.get("apiKeys.properties");
        if (Files.exists(path)){
            Properties properties = new Properties();
            properties.load(Files.newInputStream(path));
            return new ApiKeysBinance(properties.getProperty("apiKey"), properties.getProperty("secret"));
        }else {
            Properties props = new Properties();
            props.setProperty("apiKey", "");
            props.setProperty("secret", "");

            try (OutputStream os = Files.newOutputStream(path)) {
                props.store(os, "apiKeys");
            }catch (IOException e) {
                return new ApiKeysBinance("", "");
            }
            return new ApiKeysBinance("", "");
        }
    }
}



