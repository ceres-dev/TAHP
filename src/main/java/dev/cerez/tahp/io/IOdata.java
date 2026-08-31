package dev.cerez.tahp.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.discord.DiscordConnector;
import dev.cerez.tahp.fuding.FundingManager;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@UtilityClass
public class IOdata {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @SneakyThrows
    public BinanceConnector.BinanceKeys loadApiKeysBinance() {
        Path path = Paths.get("apiKeys.properties");
        if (Files.exists(path)) {
            Properties properties = new Properties();
            properties.load(Files.newInputStream(path));
            return new BinanceConnector.BinanceKeys(properties.getProperty("apiKey"), properties.getProperty("secret"));
        } else {
            Properties props = new Properties();
            props.setProperty("apiKey", "");
            props.setProperty("secret", "");

            try (OutputStream os = Files.newOutputStream(path)) {
                props.store(os, "apiKeys");
            } catch (IOException e) {
                return new BinanceConnector.BinanceKeys("", "");
            }
            return new BinanceConnector.BinanceKeys("", "");
        }
    }

    private static final Path PATH_PERSISTEN_DATA_FUNDING_MANAGER = Paths.get("persistenDataFundingManager.json");

    public void savePersistenDataFundingManager(FundingManager.PersistenData persistenData) {
        try (FileWriter writer = new FileWriter(PATH_PERSISTEN_DATA_FUNDING_MANAGER.toFile())) {
            gson.toJson(persistenData, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public FundingManager.PersistenData loadPersistenDataFundingManager(FundingManager.PersistenData persistenData) {
        File file = PATH_PERSISTEN_DATA_FUNDING_MANAGER.toFile();
        if (file.exists()) {
            try (FileReader reader = new FileReader(file)) {
                return gson.fromJson(reader, FundingManager.PersistenData.class);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        } else {
            savePersistenDataFundingManager(persistenData);
            return persistenData;
        }
    }

    @SneakyThrows
    public DiscordConnector.DiscordConfig loadConfigDiscordBot() {
        DiscordConnector.DiscordConfig.DiscordConfigBuilder discordConfig = DiscordConnector.DiscordConfig.builder();
        Path path = Paths.get("apiKeys.properties");
        if (Files.exists(path)) {
            Properties properties = new Properties();
            properties.load(Files.newInputStream(path));
            discordConfig.userMaster(properties.getProperty("userMaster"));
            discordConfig.token(properties.getProperty("token"));

        } else {
            Properties props = new Properties();
            props.setProperty("userMaster", "");
            props.setProperty("token", "");

            try (OutputStream os = Files.newOutputStream(path)) {
                props.store(os, discordConfig.getClass().getSimpleName());
            } catch (IOException e) {
                return discordConfig.build();
            }
        }
        return discordConfig.build();
    }

}



