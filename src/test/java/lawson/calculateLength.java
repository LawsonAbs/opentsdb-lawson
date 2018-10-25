package lawson;

public class calculateLength {
    public static void main(String[] args) {
        test2();
    }
    public static void test2(){
        String s = "";
        System.out.println(s.indexOf("a"));//-1
        System.out.println(s.indexOf('a')!=-1);//-1
    }
    public static void test(){
        int a = 10;
        String str = "20180725162552920000";
        String str2 = "20180725165144616";
        System.out.println(str.length());
        System.out.println(str2.length());
    }
}
