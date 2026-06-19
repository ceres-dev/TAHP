package dev.cerez.tahp.engine.model;

import lombok.Getter;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Getter
public class NameAsset {
    public final String asset;
    public final int hashPrimitive;
    public final Integer hashObject;
    public final int index;

    private static final ConcurrentMap<String, Byte[]> cacheBytes = new ConcurrentHashMap<>();

    public NameAsset(String asset, Integer index) {
        this.asset = asset;
        this.hashPrimitive = asset.hashCode();
        this.hashObject = asset.hashCode();
        this.index = index;
    }

    public NameAsset(String asset) {
        this(asset, -1);
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof NameAsset nameAsset) {
            return Objects.equals(nameAsset.hashPrimitive, this.hashPrimitive);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return hashPrimitive;
    }
}