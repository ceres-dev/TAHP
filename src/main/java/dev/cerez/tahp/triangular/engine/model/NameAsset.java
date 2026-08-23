package dev.cerez.tahp.triangular.engine.model;

import lombok.Getter;
import lombok.ToString;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

@Getter
@ToString
public class NameAsset {
    private final String name;
    private final int hashPrimitive;
    private final Integer hashObject;
    private final int index;
    public int hashOffset;

    private static int i;

    @Contract(pure = true)
    public NameAsset(@NotNull String name, Integer index) {
        this.name = name;
        this.hashPrimitive = name.hashCode();
        this.hashObject = hashPrimitive;
        this.index = index;
    }

    public NameAsset(String name) {
        this(name, -1);
    }

    public void moveOffset() {
        this.hashOffset++;
    }

    public int getHashPrimitive(){
        return this.index;
    }

    public Integer cacheInteger = -1;

    public Integer getHashObject(){
        return cacheInteger == -1 ? cacheInteger = this.index : cacheInteger;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof NameAsset nameAsset) {
            return nameAsset.hashCode() == this.hashCode();
        }
        return false;
    }

    @Override
    public int hashCode() {
        return index;
    }
}
