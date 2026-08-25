package dev.cerez.tahp.io;

import dev.cerez.tahp.connector.BaseConnector;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
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

    @SneakyThrows
    public BinanceConnector.BinanceKeys loadApiKeysBinance() {
        Path path = Paths.get("apiKeys.properties");
        if (Files.exists(path)){
            Properties properties = new Properties();
            properties.load(Files.newInputStream(path));
            return new BinanceConnector.BinanceKeys(properties.getProperty("apiKey"), properties.getProperty("secret"));
        }else {
            Properties props = new Properties();
            props.setProperty("apiKey", "");
            props.setProperty("secret", "");

            try (OutputStream os = Files.newOutputStream(path)) {
                props.store(os, "apiKeys");
            }catch (IOException e) {
                return new BinanceConnector.BinanceKeys("", "");
            }
            return new BinanceConnector.BinanceKeys("", "");
        }
    }
}



