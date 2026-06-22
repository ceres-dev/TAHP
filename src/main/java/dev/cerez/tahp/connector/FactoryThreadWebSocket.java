package dev.cerez.tahp.connector;

import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ThreadFactory;

public class FactoryThreadWebSocket implements ThreadFactory {

    private int i = 0;

    @Override
    public Thread newThread(@NotNull Runnable r){
        Thread t = new Thread(r);
        t.setName("stream-webSocket-" + i);
        i++;
        return t;
    }
}
