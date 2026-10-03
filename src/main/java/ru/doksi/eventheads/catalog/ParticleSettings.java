package ru.doksi.eventheads.catalog;

/** Per-particle visual settings stored for an EventHeads event. */
public final class ParticleSettings {
    public double radius = 0.15D;
    public long durationSeconds = -1L; // -1 = whole animation
    public int count = -1;             // -1 = use global/event total distribution
    public double speed = 0.01D;
    public float size = 1.0F;
    public String color = "";          // empty = inherit event/global color list
    public int note = -1;              // legacy single-note value; -1 = no note selected
    public final java.util.List<Integer> notes = new java.util.ArrayList<>(); // multiple NOTE values 0..24

    public ParticleSettings copy() {
        ParticleSettings p = new ParticleSettings();
        p.radius = radius;
        p.durationSeconds = durationSeconds;
        p.count = count;
        p.speed = speed;
        p.size = size;
        p.color = color;
        p.note = note;
        p.notes.addAll(notes);
        return p;
    }
}
