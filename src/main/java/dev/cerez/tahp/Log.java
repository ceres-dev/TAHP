package dev.cerez.tahp;

import lombok.Data;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
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
        LOGGER.error(formatColor(String.format(message, o) + "<reset>"));
    }

    public static synchronized void error(String message) {
        LOGGER.error(formatColor("<red_light>" + message + "<reset>"));
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
            s = s.replace("<" + color.name().toLowerCase(Locale.ROOT) + ">", selectColor(color.red, color.green, color.blue));
        }
        return s;
    }

    public static final String CLEAR = "\u001B[0m";

    /**
     *
     * selects a color using rgb/hex and converts to ANSI code
     *
     * @param r - Red value (in decimal)
     * @param g - Green value (in decimal)
     * @param b - Blue value (in decimal)
     * @return escape string with set rgb value
     * @author <a href="https://gist.github.com/cindrmon">By cindrmon</a>
     */
    private static String selectColor(int r, int g, int b) {
        if (r == -1 || g == -1 || b == -1) {
            return CLEAR;
        }
        if (r <= 255 && g <= 255 && b <= 255 && r >= 0 && g >= 0 && b >= 0)
            return "\u001B[38;2;" + r + ";" + g + ";" + b + "m";
        else
            return "\u001B[38;2;255;255;255m";
    }


    @Getter
    @RequiredArgsConstructor
    private enum Colors{
        RESET(-1, -1, -1),
        RED(255, 0, 0),
        RED_LIGHT(255, 128, 128),
        GREEN(0, 255, 0),
        YELLOW(255, 255, 0),
        GREEN_YELLOW(128+64, 255, 0),
        BLUE(0, 0, 255),
        CIAN(0, 255, 255),;

        private final int red;
        private final int green;
        private final int blue;
    }

}
