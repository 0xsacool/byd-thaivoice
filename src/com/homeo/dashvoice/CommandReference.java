package com.homeo.dashvoice;

/**
 * The on-screen command reference.
 *
 * <p>The grammar in {@link Commands} holds 128 entries, most of which are
 * pronunciation aliases — "fan two" also accepts "fan to" and "fan too"
 * because the small English Vosk model mishears digits. Listing all of them
 * would be noise, so each group here shows the phrasing worth remembering and
 * notes the alternatives in prose.
 *
 * <p>{@link Group#status} records what actually happens on this vehicle, so
 * the screen never advertises something the car refuses.
 */
final class CommandReference {

    enum Status { WORKS, BLOCKED, ABSENT }

    static final class Group {
        final String title;
        final String[] phrases;
        final String note;      // nullable
        final Status status;

        Group(String title, Status status, String note, String... phrases) {
            this.title = title;
            this.status = status;
            this.note = note;
            this.phrases = phrases;
        }
    }

    static final Group[] GROUPS = {

        new Group("เปิดและปิดแอร์", Status.WORKS,
                "เปิดหรือปิดแอร์",
                "ac on",
                "ac off"),

        new Group("ปรับตามความรู้สึก", Status.WORKS,
                "ปรับอุณหภูมิและพัดลมตามค่าที่กำหนด",
                "cool it down",
                "warm it up",
                "comfort mode"),

        new Group("อุณหภูมิ", Status.WORKS,
                "รองรับ 17–30 องศาตาม API ของรถ",
                "temperature twenty two",
                "warmer",
                "cooler",
                "passenger warmer"),

        new Group("ความแรงพัดลม", Status.WORKS,
                "รองรับระดับ 1–7 ตาม API ของรถ",
                "fan three",
                "fan max",
                "fan low",
                "fan up",
                "fan down"),

        new Group("ทิศทางลม", Status.WORKS,
                "เลือกทิศทางลม",
                "vent face",
                "vent feet",
                "vent face and feet",
                "vent everywhere"),

        new Group("ไล่ฝ้า", Status.WORKS,
                "เลือกไล่ฝ้ากระจกหน้าและหลัง",
                "defrost windshield",
                "max defrost",
                "rear defrost",
                "rear defrost off"),

        new Group("การหมุนเวียนอากาศ", Status.WORKS,
                "เลือกอากาศภายในหรือภายนอก",
                "recirculate",
                "fresh air"),

        new Group("โหมดแอร์", Status.WORKS,
                "เลือกโหมดอัตโนมัติหรือควบคุมเอง",
                "auto mode",
                "manual mode",
                "max cooling",
                "ventilation on",
                "fan only"),

        new Group("คอมเพรสเซอร์", Status.WORKS, null,
                "compressor on",
                "compressor off"),

        new Group("เปิดแอป", Status.WORKS,
                "ขึ้นอยู่กับแอปที่ติดตั้ง",
                "open music",
                "open maps",
                "open settings"),

        new Group("ค่าแอร์ที่บันทึก", Status.WORKS,
                "เรียกค่าแอร์ที่บันทึกไว้",
                "my preset"),

        new Group("กระจกหน้าต่าง", Status.BLOCKED,
                "ยังไม่รองรับใน Alpha 1",
                "close windows",
                "open driver window"),

        new Group("ซันรูฟ", Status.ABSENT,
                "ยังไม่รองรับใน Alpha 1",
                "open sunroof",
                "close sunroof"),
    };

    static String labelFor(String phrase) {
        switch (phrase) {
            case "ac on": return "เปิดแอร์";
            case "ac off": return "ปิดแอร์";
            case "cool it down": return "ปรับให้เย็นลง";
            case "warm it up": return "ปรับให้อุ่นขึ้น";
            case "comfort mode": return "โหมดสบาย";
            case "temperature twenty two": return "ตั้ง 22 องศา";
            case "warmer": return "เพิ่มอุณหภูมิ";
            case "cooler": return "ลดอุณหภูมิ";
            case "passenger warmer": return "เพิ่มอุณหภูมิฝั่งผู้โดยสาร";
            case "fan three": return "พัดลมระดับ 3";
            case "fan max": return "พัดลมแรงสุด";
            case "fan low": return "พัดลมเบา";
            case "fan up": return "เพิ่มพัดลม";
            case "fan down": return "ลดพัดลม";
            case "vent face": return "ลมเป่าหน้า";
            case "vent feet": return "ลมเป่าเท้า";
            case "vent face and feet": return "ลมเป่าหน้าและเท้า";
            case "vent everywhere": return "ลมทุกทิศทาง";
            case "defrost windshield": return "ไล่ฝ้ากระจกหน้า";
            case "max defrost": return "ไล่ฝ้าสูงสุด";
            case "rear defrost": return "ไล่ฝ้ากระจกหลัง";
            case "rear defrost off": return "ปิดไล่ฝ้ากระจกหลัง";
            case "recirculate": return "หมุนเวียนอากาศในรถ";
            case "fresh air": return "รับอากาศภายนอก";
            case "auto mode": return "โหมดอัตโนมัติ";
            case "manual mode": return "โหมดแมนนวล";
            case "max cooling": return "เย็นสูงสุด";
            case "ventilation on": return "เปิดระบายอากาศ";
            case "fan only": return "เฉพาะพัดลม";
            case "compressor on": return "เปิดคอมเพรสเซอร์";
            case "compressor off": return "ปิดคอมเพรสเซอร์";
            case "open music": return "เปิดเพลง";
            case "open maps": return "เปิดแผนที่";
            case "open settings": return "เปิดการตั้งค่า";
            case "my preset": return "เรียกค่าแอร์ที่บันทึก";
            case "close windows": return "ปิดกระจก";
            case "open driver window": return "เปิดกระจกฝั่งคนขับ";
            case "open sunroof": return "เปิดซันรูฟ";
            case "close sunroof": return "ปิดซันรูฟ";
            default: return phrase;
        }
    }

    private CommandReference() {}
}
