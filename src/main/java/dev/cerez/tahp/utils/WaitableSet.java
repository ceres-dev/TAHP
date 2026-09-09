package dev.cerez.tahp.utils;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class WaitableSet<T> {

    private final Set<T> elements = new HashSet<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition emptyCondition = lock.newCondition();

    public void add(T element) {
        lock.lock();
        try {
            elements.add(element);
        } finally {
            lock.unlock();
        }
    }

    public void addAll(Iterable<T> elements) {
        lock.lock();
        try {
            for (T element : elements) {
                this.elements.add(element);
            }
        } finally {
            lock.unlock();
        }
    }
    public void addAll(Collection<T> elements) {
        lock.lock();
        try {
            this.elements.addAll(elements);
        } finally {
            lock.unlock();
        }
    }

    public void remove(T element) {
        lock.lock();
        try {
            if (elements.remove(element) && elements.isEmpty()) {
                System.out.println("Removed element " + element);
                emptyCondition.signalAll();
            }
        } finally {
            lock.unlock();
        }
    }

    public boolean isEmpty() {
        lock.lock();
        try {
            return elements.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    public void awaitEmpty() throws InterruptedException {
        while (!elements.isEmpty()) {
            emptyCondition.await();
        }
    }

    public int size() {
        lock.lock();
        try {
            return elements.size();
        } finally {
            lock.unlock();
        }
    }

    public void clear() {
        lock.lock();
        try {
            elements.clear();
            emptyCondition.signalAll();
        } finally {
            lock.unlock();
        }
    }
}