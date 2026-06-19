package dev.cerez.tahp;

import lombok.Getter;
import lombok.experimental.UtilityClass;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;

@UtilityClass
public class Log {


    private static final Logger LOGGER = LogManager.getLogger(Log.class);

    public static synchronized void info(String message, Object... o) {
        LOGGER.info(formatColor(String.format(message, o) + "<reset>"));
    }
    public static synchronized void info(String message) {
        LOGGER.info(formatColor(message + "<reset>"));
    }

    public static synchronized void warning(String message, Object... o) {
        LOGGER.warn(formatColor(String.format(message, o) + "<reset>"));
    }


    public static synchronized void warning(String message) {
        LOGGER.warn(formatColor(message + "<reset>"));
    }

    public static synchronized void error(String message, Object... o) {
        LOGGER.error(formatColor(String.format(message) + "<reset>"), o);
    }

    public static synchronized void error(String message) {
        LOGGER.error(formatColor(message + "<reset>"));
    }

    public static synchronized void clearLine(){
        System.out.print("\r " + " ".repeat(150) + "\r");
    }
    public static synchronized void exception(String message, Exception exception) {
//        LOGGER.error(setFormatException(message, exception));
        exception.printStackTrace();
    }


    private static String formatColor(String s){
        for (Colors color : Colors.values()) {
            s = s.replace("<" + color.name().toLowerCase(Locale.ROOT) + ">", color.getColor());
        }
        return s;
    }


    @Getter
    private enum Colors{
        RESET ("\u001B[0m"),
        RED("\u001B[31m"),
        GREEN("\u001B[32m"),
        YELLOW("\u001B[33m"),
        BLUE("\u001B[34m"),
        CIAN("\u001B[36m");

        private final String color;

        Colors(String s){
            this.color = s;
        }
    }

}
