package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.command.InputUser;
import dev.cerez.tahp.fuding.BlockerForSpread;
import dev.cerez.tahp.fuding.FundingManager;
import dev.cerez.tahp.fuding.TestFunding;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;

public class FundingCommand extends BaseCommand {
    public FundingCommand() {
        super("funding");
    }

    private final FundingManager.FundingManagerConfig.FundingManagerConfigBuilder configBuilder = FundingManager.FundingManagerConfig.builder();
    private FundingManager fundingManager = null;

    private BigDecimal entry = BigDecimal.ONE;
    private BigDecimal exit = BigDecimal.ONE;

    private InputUser input = new InputUser();

    @Override
    public void execute(@NotNull List<String> args) {
        if (args.isEmpty()) {
            Log.error("no arguments supplied");
            return;
        }
        String action = args.getFirst();
        switch (action) {
            case "config" -> {
                List<String> subArg = args.subList(1, args.size()-1);
                if (subArg.isEmpty()) {
                    Log.info("Config: \n%s", configBuilder.build());
                }
                if (subArg.size() > 1) {
                    switch (subArg.getFirst()) {
                        case "sizePosition" -> configBuilder.sizePosition(new BigDecimal(subArg.get(1)));
                        case "booking" -> configBuilder.booking(new BigDecimal(subArg.get(1)));
                        case "baseAsset" -> configBuilder.baseAsset(subArg.get(1));
                        case "quoteAsset" -> configBuilder.quoteAsset(subArg.get(1));
                        case "logsEndPoints" -> configBuilder.logsEndPoints(Boolean.parseBoolean(subArg.get(1)));
                    }
                }
            }
            case "startNow" -> {
                if (fundingManager != null && fundingManager.isStarted()) {
                    Log.error("Ya esta iniciado");
                    return;
                }
                if (!input.inBoolean("¿Estas seguro de iniciar ahora?")){
                    return;
                }
                Log.info("Iniciando...");
                fundingManager = new FundingManager(configBuilder.build());
                fundingManager.start();
            }
            case "start" -> {
                if (fundingManager != null && fundingManager.isStarted()) {
                    Log.error("Ya esta iniciado");
                    return;
                }
                Log.info("EntrySpreed: %.2f%%", entry.doubleValue()*100);
                Log.info("Esperando...");
                FundingManager.FundingManagerConfig build = configBuilder.build();
                fundingManager = new FundingManager(build);
                BlockerForSpread blocking = new BlockerForSpread(fundingManager.getConnector(), fundingManager.getSymbol());
                blocking.waitEntrySpred(entry);
                Log.info("Iniciando...");
                fundingManager.start();
            }
            case "stopNow" -> {
                if (fundingManager == null) {
                    Log.error("No se a creado el gesto aún");
                    return;
                }
                if (!fundingManager.isStarted()) {
                    Log.error("No esta iniciado");
                    return;
                }
                Log.info("Deteniendo...");
                fundingManager.stop();
            }
            case "stop" -> {
                Log.info("ExitSpreed: %.2f%%", exit.doubleValue()*100);
                if (fundingManager == null) {
                    Log.error("No se a creado el gesto aún");
                    return;
                }
                if (!fundingManager.isStarted()) {
                    Log.error("No esta iniciado");
                    return;
                }
                BlockerForSpread blocking = new BlockerForSpread(fundingManager.getConnector(), fundingManager.getSymbol());
                blocking.waitExitSpread(entry);
                Log.info("Deteniendo...");
                fundingManager.stop();
            }
            case "check" -> new TestFunding().run(configBuilder.build());
            case "entrySpread" -> {
                List<String> subArg = args.subList(1, args.size()-1);
                if (subArg.isEmpty()) {
                    Log.info("SpreadEntry: %.2f" + entry.doubleValue());
                }else {
                    entry = new BigDecimal(subArg.getFirst());
                }
            }
            case "exitSpread" -> {
                List<String> subArg = args.subList(1, args.size()-1);
                if (subArg.isEmpty()) {
                    Log.info("SpreadExit: %.2f" + exit.doubleValue());
                }else {
                    exit = new BigDecimal(subArg.getFirst());
                }
            }
        }

    }
}
