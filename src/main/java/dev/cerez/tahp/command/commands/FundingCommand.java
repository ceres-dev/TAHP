package dev.cerez.tahp.command.commands;

import dev.cerez.tahp.Log;
import dev.cerez.tahp.command.BaseCommand;
import dev.cerez.tahp.command.InputUser;
import dev.cerez.tahp.discord.DiscordConnector;
import dev.cerez.tahp.fuding.BlockerForSpread;
import dev.cerez.tahp.fuding.FundingManager;
import dev.cerez.tahp.fuding.TestFunding;
import dev.cerez.tahp.io.IOdata;
import lombok.ToString;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.util.List;

@ToString
public class FundingCommand extends BaseCommand {
    public FundingCommand() {
        super("funding", "f");
    }


    private final InputUser input = new InputUser();
    private final FundingManager.FundingManagerConfig.FundingManagerConfigBuilder configBuilder = FundingManager.FundingManagerConfig.builder();
    private FundingManager fundingManager = null;
    private DiscordConnector discordConnector = null;

    private BigDecimal entry = BigDecimal.ONE;
    private BigDecimal exit = BigDecimal.ONE;

    @Override
    public void execute(@NotNull List<String> args) {
        if (args.isEmpty()) {
            Log.error("no arguments supplied");
            return;
        }
        String action = args.getFirst();
        switch (action) {
            case "config" -> {
                if (args.size() == 1) {
                    Log.info("Config: \n%s", configBuilder.build());
                    return;
                }
                List<String> subArg = args.subList(1, args.size());
                if (subArg.size() > 1) {
                    switch (subArg.getFirst()) {
                        case "sizePosition" -> configBuilder.sizePosition(new BigDecimal(subArg.get(1)));
                        case "booking" -> configBuilder.booking(new BigDecimal(subArg.get(1)));
                        case "baseAsset" -> configBuilder.baseAsset(subArg.get(1));
                        case "quoteAsset" -> configBuilder.quoteAsset(subArg.get(1));
                        case "logsEndPoints" -> configBuilder.logsEndPoints(Boolean.parseBoolean(subArg.get(1)));
                    }
                }else {
                    switch (subArg.getFirst()) {
                        case "load" -> {
                            var config = IOdata.loadConfig(configBuilder.build().getClass());
                            configBuilder.baseAsset(config.getBaseAsset());
                            configBuilder.quoteAsset(config.getQuoteAsset());
                            configBuilder.sizePosition(config.getSizePosition());
                            configBuilder.booking(config.getBooking());
                            configBuilder.logsEndPoints(config.isLogsEndPoints());
                        }
                        case "save" -> IOdata.saveConfig(configBuilder.build());
                    }
                }
            }
            case "configure" -> {
                this.fundingManager = new FundingManager(configBuilder.build());
            }
            case "startNow" -> {
                if (fundingManager.isStarted()) {
                    Log.error("Ya esta iniciado");
                    return;
                }
                if (!input.inBoolean("¿Estas seguro de iniciar ahora?")){
                    return;
                }
                Log.info("Iniciando...");
                fundingManager.start();
            }
            case "start" -> {
                if (fundingManager.isStarted()) {
                    Log.error("Ya esta iniciado");
                    return;
                }
                Log.info("EntrySpreed: %.2f%%", entry.doubleValue()*100);
                Log.info("Esperando...");
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
                List<String> subArg = args.subList(1, args.size());
                if (subArg.isEmpty()) {
                    Log.info("SpreadEntry: %.2f%%" + entry.doubleValue()*100);
                }else {
                    entry = new BigDecimal(subArg.getFirst());
                }
            }
            case "exitSpread" -> {
                if (args.size() == 1) {
                    Log.info("SpreadExit: %.2f%%" + exit.doubleValue()*100);
                    return;
                }
                List<String> subArg = args.subList(1, args.size()-1);
                exit = new BigDecimal(subArg.getFirst());
            }
            case "discord" -> {
                if (args.size() == 1) {
                    Log.info("Discord: %s", discordConnector);
                    return;
                }
                List<String> subArg = args.subList(1, args.size());
                switch (subArg.getFirst()) {
                    case "configure" -> discordConnector = new DiscordConnector();
                    case "start" -> {
                        if (discordConnector == null) {
                            Log.error("configura el bot primero");
                            return;
                        }
                        discordConnector.start();
                        discordConnector.setStatusProfiler(fundingManager);
                    }
                    case "stop" -> {
                        if (discordConnector == null) {
                            Log.error("configura el bot primero");
                            return;
                        }
                        discordConnector.stop();
                        discordConnector = null;
                    }
                }
            }
        }
    }
}
