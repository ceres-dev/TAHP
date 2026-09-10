package dev.cerez.tahp.command.commands;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.connector.connectors.BinanceConnector;
import dev.cerez.tahp.connector.connectors.exception.binance.SystemNotEnoughAssetException;
import dev.cerez.tahp.connector.model.Symbol;
import lombok.SneakyThrows;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

public class DataRecoveryCommand extends BaseCommand {
    public DataRecoveryCommand() {
        super("dataRecovery", "dr");
    }

    @SneakyThrows
    @Override
    public void execute(@NotNull List<String> args) {
        FundingDataStorage fundingDataStorage = new FundingDataStorage();
        BinanceConnector connector = new BinanceConnector();
        connector.loadApikey();
        connector.runLoopers();
        connector.syncTimeServer();
        Map<String, FundingData> data = fundingDataStorage.loadLatest();
        Map<String, Symbol> spotSymbol = connector.sGetAllSymbols();
        Map<String, Symbol> futureSymbol = connector.sGetAllSymbols();
        while (true) {
            Map<String, BinanceConnector.FundingRate> funding = connector.fGetFundingRate();

            List<BinanceConnector.FundingRate> top35 = funding.values().stream()
                    .filter(f -> f.symbol().endsWith("USDT"))
                    .filter(f -> spotSymbol.containsKey(f.symbol()))
                    .filter(f -> futureSymbol.containsKey(f.symbol()))
                    .sorted(Comparator.comparing(BinanceConnector.FundingRate::nextFundingRate))
                    .limit(35)
                    .toList();
            Set<String> symbolRecopile = data.keySet();
            List<BinanceConnector.FundingRate> prevRecopile = funding.values().stream()
                    .filter(f ->  symbolRecopile.contains(f.symbol()))
                    .toList();

            Set<BinanceConnector.FundingRate> total = new HashSet<>();
            total.addAll(top35);
            total.addAll(prevRecopile);

            long date = System.currentTimeMillis();
            Map<String, FundingData> newData = new HashMap<>();
            for (BinanceConnector.FundingRate fundingRate : total) {
                BigDecimal maxBorrowable;
                try {
                    maxBorrowable = connector.mGetMaxAmountBorrowable(null, fundingRate.symbol().replace("USDT", ""));
                } catch (SystemNotEnoughAssetException e) {
                    maxBorrowable = new BigDecimal("-1");
                }
                newData.put(fundingRate.symbol(), new FundingData(date, fundingRate.nextFundingRate(), maxBorrowable));
            }
            fundingDataStorage.save(newData);
            Log.info("Datos guardas symbols=%s", total.stream().map(BinanceConnector.FundingRate::symbol).toList());
            LockSupport.parkNanos(TimeUnit.MINUTES.toNanos(10));
        }
    }

    private static class FundingDataStorage {

        private static final Path BASE_PATH = Path.of("funding-data");
        private final Gson gson = new GsonBuilder()
//                .setPrettyPrinting()
                .create();
        private final Type listType = new TypeToken<List<FundingData>>() {}.getType();


        private void save(@NotNull Map<String, FundingData> data) throws IOException {

            LocalDate today = LocalDate.now();

            for (Map.Entry<String, FundingData> entry : data.entrySet()) {

                String symbol = entry.getKey();
                FundingData newData = entry.getValue();

                Path symbolDirectory = BASE_PATH.resolve(symbol);
                Files.createDirectories(symbolDirectory);

                Path file = symbolDirectory.resolve(today + ".json");

                List<FundingData> history;

                if (Files.exists(file)) {
                    try (Reader reader = Files.newBufferedReader(file)) {
                        history = gson.fromJson(reader, listType);

                        if (history == null) {
                            history = new ArrayList<>();
                        }
                    }
                } else {
                    history = new ArrayList<>();
                }

                history.add(newData);

                try (Writer writer = Files.newBufferedWriter(file)) {
                    gson.toJson(history, listType, writer);
                }
            }
        }

        /**
         * Carga únicamente el último archivo existente de cada símbolo.
         *
         * @return mapa donde key = símbolo y value = último dato guardado
         */
        public Map<String, FundingData> loadLatest() throws IOException {

            Map<String, FundingData> result = new HashMap<>();

            if (!Files.exists(BASE_PATH)) {
                return result;
            }

            try (var symbols = Files.list(BASE_PATH)) {

                for (Path symbolDirectory : symbols
                        .filter(Files::isDirectory)
                        .toList()) {

                    Path latestFile;

                    try (var files = Files.list(symbolDirectory)) {
                        latestFile = files
                                .filter(Files::isRegularFile)
                                .filter(path -> path.getFileName().toString().endsWith(".json"))
                                .max(Comparator.comparing(path -> path.getFileName().toString()))
                                .orElse(null);
                    }

                    if (latestFile == null) {
                        continue;
                    }

                    try (Reader reader = Files.newBufferedReader(latestFile)) {

                        List<FundingData> history = gson.fromJson(reader, listType);

                        if (history != null && !history.isEmpty()) {
                            result.put(
                                    symbolDirectory.getFileName().toString(),
                                    history.getLast()
                            );
                        }
                    }
                }
            }

            return result;
        }
    }

    private record FundingData(
            long date,
            BigDecimal fundingRate,
            BigDecimal borrowAvailable
    ) {}
}
