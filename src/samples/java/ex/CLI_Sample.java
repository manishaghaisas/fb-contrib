package ex;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;

@SuppressWarnings("all")
public class CLI_Sample {
    public String test1(String[] sa) {
        String result = "";
        for (String element : sa)
            result += sa[0];
        return result;
    }

    public String test2(String[] sa) {
        String name = sa[0];
        String address = sa[1];
        String ss = sa[2];
        return name + address + ss;
    }

    public String testFPSplit(String s) {
        String[] parts = s.split(",");
        String a = parts[0];
        String b = parts[1];
        String c = parts[2];
        return c + b + a;
    }

    class FPHander implements InvocationHandler {
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args[2] == null) {
                return null;
            }

            return String.valueOf(args[0]) + String.valueOf(args[1]);
        }
    }
}
