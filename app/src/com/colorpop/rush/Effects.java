package com.colorpop.rush;

/** Lightweight visual-effect value objects: pop particles and floating texts. */
public final class Effects {

    private Effects() {}

    /** A small burst particle (used for the pop confetti). */
    public static final class Particle {
        public float x, y, vx, vy, radius;
        public int color;
        public float life, maxLife;

        public Particle(float x, float y, float vx, float vy, float radius, int color, float life) {
            this.x = x;
            this.y = y;
            this.vx = vx;
            this.vy = vy;
            this.radius = radius;
            this.color = color;
            this.life = life;
            this.maxLife = life;
        }

        /** @return false when the particle has expired. */
        public boolean update(float dt, float gravity) {
            x += vx * dt;
            y += vy * dt;
            vy += gravity * dt;
            vx *= 0.98f;
            life -= dt;
            return life > 0f;
        }

        public float alpha() {
            return Math.max(0f, life / maxLife);
        }
    }

    /** Rising, fading text such as "+120" or "COMBO x3". */
    public static final class FloatingText {
        public String text;
        public float x, y, vy, age, maxAge, size;
        public int color;
        public boolean bold;

        public FloatingText(String text, float x, float y, float size, int color, float maxAge, boolean bold) {
            this.text = text;
            this.x = x;
            this.y = y;
            this.vy = -size * 1.4f;
            this.size = size;
            this.color = color;
            this.maxAge = maxAge;
            this.bold = bold;
        }

        public boolean update(float dt) {
            age += dt;
            y += vy * dt;
            vy *= 0.92f;
            return age < maxAge;
        }

        /** Eased-in scale that overshoots slightly then settles (back-ease pop). */
        public float scale() {
            float t = Math.min(1f, age / (maxAge * 0.35f));
            float u = t - 1f;
            float back = 1f + 2.70158f * u * u * u + 1.70158f * u * u; // easeOutBack
            return 0.4f + 0.7f * back;
        }

        public float alpha() {
            float fadeStart = maxAge * 0.55f;
            if (age < fadeStart) {
                return 1f;
            }
            return Math.max(0f, 1f - (age - fadeStart) / (maxAge - fadeStart));
        }
    }
}
