package dev.cerez.tahp.utils;

import lombok.experimental.UtilityClass;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.UUID;

@UtilityClass
public class Utils {


    public @NotNull String uuidToBase36(UUID uuid) {
        BigInteger value = uuidToBigInteger(uuid);
        return value.toString(36);
    }

    private @NotNull BigInteger uuidToBigInteger(@NotNull UUID uuid) {
        return BigInteger.valueOf(uuid.getMostSignificantBits())
                .shiftLeft(64)
                .or(BigInteger.valueOf(uuid.getLeastSignificantBits())
                        .and(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)));
    }

    @Contract("_ -> new")
    public static @NotNull BigDecimal toDecimal(int value) {
        return BigDecimal.valueOf(Math.pow(10, -value));
    }

}
