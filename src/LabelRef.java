/**
 * LabelRef - ตำแหน่งของ label หนึ่งตัวหลังผ่าน pass แรก (scanLabels)
 *
 * addr -> word address ในหน่วยความจำโปรแกรม (นับเป็น "word" ไม่ใช่ "byte"
 *         และไม่ใช่ "บรรทัด" ด้วย เพราะบางคำสั่งกิน 2 words เช่น JMP/LIM/
 *         STORE/LOAD จึงต้องสะสม word length จริงระหว่าง scan)
 */
class LabelRef {
    final int addr;
    LabelRef(int addr) {
        this.addr = addr;
    }
}
