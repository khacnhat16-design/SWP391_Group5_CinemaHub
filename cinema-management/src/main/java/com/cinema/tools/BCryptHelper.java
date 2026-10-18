package com.cinema.tools;

import org.mindrot.jbcrypt.BCrypt;

/** Utility tạm thời để in ra hash BCrypt thật cho seed SQL.
 *  Chạy: mvn exec:java -Dexec.mainClass=com.cinema.tools.BCryptHelper
 *  hoặc: java -cp ... com.cinema.tools.BCryptHelper Admin@123
 *  Sau khi lấy hash, cập nhật auth_seed.sql — KHÔNG commit class này vào production. */
public final class BCryptHelper {
    private BCryptHelper() {}
    public static void main(String[] args) {
        String[] defaults = { "Admin@123", "Manager@123", "Staff@123", "Customer@123" };
        String[] inputs = args.length == 0 ? defaults : args;
        for (String pw : inputs) {
            System.out.println(pw + "\t" + BCrypt.hashpw(pw, BCrypt.gensalt(12)));
        }
    }
}
