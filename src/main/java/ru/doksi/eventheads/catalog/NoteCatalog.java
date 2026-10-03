package ru.doksi.eventheads.catalog;

import java.util.List;

/** 25 Minecraft note-block notes with the colors supplied by the server owner. */
public final class NoteCatalog {
    private NoteCatalog() {}
    public record Note(int id, String name, double pitch, String hex, String shortName) {}
    private static final List<Note> NOTES = List.of(
            new Note(0,  "F♯/G♭", 0.500000, "#59E800", "Fi/Se"),
            new Note(1,  "G",     0.529732, "#82CE00", "Sol"),
            new Note(2,  "G♯/A♭", 0.561231, "#ACAC00", "Si/Le"),
            new Note(3,  "A",     0.594604, "#CE8400", "La"),
            new Note(4,  "A♯/B♭", 0.629961, "#E85900", "Li/Te"),
            new Note(5,  "B",     0.667420, "#F92E00", "Ti"),
            new Note(6,  "C",     0.707107, "#FF0606", "Do"),
            new Note(7,  "C♯/D♭", 0.749154, "#F9002E", "Di/Ra"),
            new Note(8,  "D",     0.793701, "#E80059", "Re"),
            new Note(9,  "D♯/E♭", 0.840896, "#CE0082", "Ri/Me"),
            new Note(10, "E",     0.890899, "#AC00AC", "Mi"),
            new Note(11, "F",     0.943874, "#8200CE", "Fa"),
            new Note(12, "F♯/G♭", 1.000000, "#5900E8", "Fi/Se"),
            new Note(13, "G",     1.059463, "#2E00F9", "Sol"),
            new Note(14, "G♯/A♭", 1.122462, "#0606FF", "Si/Le"),
            new Note(15, "A",     1.189207, "#002EF9", "La"),
            new Note(16, "A♯/B♭", 1.259921, "#0059E8", "Li/Te"),
            new Note(17, "B",     1.334840, "#0082CE", "Ti"),
            new Note(18, "C",     1.414214, "#00ACAC", "Do"),
            new Note(19, "C♯/D♭", 1.498307, "#00CE82", "Di/Ra"),
            new Note(20, "D",     1.587401, "#00E859", "Re"),
            new Note(21, "D♯/E♭", 1.681793, "#00F92E", "Ri/Me"),
            new Note(22, "E",     1.781797, "#06FF06", "Mi"),
            new Note(23, "F",     1.887749, "#2EF900", "Fa"),
            new Note(24, "F♯/G♭", 2.000000, "#59E800", "Fi/Se")
    );
    public static List<Note> all(){ return NOTES; }
    public static Note get(int id){ return id>=0 && id<NOTES.size()?NOTES.get(id):NOTES.get(6); }
}
