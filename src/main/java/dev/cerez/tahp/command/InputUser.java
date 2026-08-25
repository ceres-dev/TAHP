package dev.cerez.tahp.command;

import org.jetbrains.annotations.Blocking;

import java.util.Scanner;

public class InputUser {

    @Blocking
    public String in(String label){
        Scanner sc = new Scanner(System.in);
        System.out.printf("\u001B[38;2;0;255;255m%s > \u001B[0m", label);
        return sc.nextLine();
    }

    @Blocking
    public boolean inBoolean(String label){
        Scanner sc = new Scanner(System.in);
        System.out.printf("\u001B[38;2;0;255;255m%s (Y/N) > \u001B[0m", label);
        return sc.nextLine().equalsIgnoreCase("y");
    }


}
