/**
 * Token - ผลลัพธ์จาก lexer หนึ่งตัว
 *
 * type: 1=ID (mnemonic/register/label name), 2=INT (decimal หรือ 0x hex),
 *       3=STRING, 4=BOOL, 5=CHAR
 * (ตัด BYTE type ของ compiler เดิมออก เพราะ CPU16 ไม่มี concept "byte literal"
 *  แยกจาก int -- เลข 0x.. ทั้งหมดคือค่าจำนวนเต็ม 16 บิตตามปกติ)
 */
class Token {
    public String StrValue = "";
    public int IntValue = 0;
    public boolean BoolValue = false;
    public char CharValue = '\0';
    public int type = 0;

    public static final int ID = 1;
    public static final int INT = 2;
    public static final int STRING = 3;
    public static final int BOOL = 4;
    public static final int CHAR = 5;
}
