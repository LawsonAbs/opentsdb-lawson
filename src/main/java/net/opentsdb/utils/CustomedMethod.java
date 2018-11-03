package net.opentsdb.utils;

import java.text.SimpleDateFormat;
import java.util.Date;

public class CustomedMethod {
    public static void printSuffix(String string){
        System.out.println(string+"    --> add by lawson");
    }

    public static void printDelimiter(String str){
        Date date = new Date();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd  HH:mm");
        System.out.println(str+"=======================" + sdf.format(date)+"======================");
    }
}
