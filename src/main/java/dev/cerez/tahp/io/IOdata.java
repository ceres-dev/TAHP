package dev.cerez.tahp.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.discord.DiscordConnector;
import dev.cerez.tahp.fuding.FundingManager;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.NotNull;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

@UtilityClass
public class IOdata {

    private static final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    private static final Path PATH_PERSISTEN_DATA_FUNDING_MANAGER = Paths.get("persistenDataFundingManager.json");
    private static final Path PATH_FUNDING_MANAGER_CONFIG = Paths.get("fundingManangerConfig.json");
    private static final Path PATH_DISCORD_CONFIG = Paths.get("discordConfig.properties");
    private static final Path PATH_API_KEYS = Paths.get("apiKeys.properties");

    @SneakyThrows
    public BinanceConnector.BinanceKeys loadApiKeysBinance() {
        Path path = PATH_API_KEYS;
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

    public void saveConfig(Object o){
        try (FileWriter writer = new FileWriter(Path.of(o.getClass().getSimpleName() + "-Config.json").toFile())) {
            gson.toJson(o, writer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public <T> T loadConfig(Class<T> t){
        try (FileReader reader = new FileReader(Path.of(t.getSimpleName() + "-Config.json").toFile())) {
            return gson.fromJson(reader, t);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> @NotNull T loadOrSaveConfig(@NotNull T o){
        T loaded = (T) loadConfig(o.getClass());
        if(loaded == null){
            saveConfig(o);
            return o;
        }else {
            return loaded;
        }
    }
}



