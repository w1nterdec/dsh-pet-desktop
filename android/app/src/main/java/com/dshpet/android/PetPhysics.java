package com.dshpet.android;

/** Frame-rate independent screen physics, expressed in pixels and seconds. */
public final class PetPhysics {
  public double x, y, vx, vy;
  public double maxX, maxY, gravity = 1400, restitution = .68;
  public boolean resting;

  public void bounds(double width, double height, double size) {
    maxX = Math.max(0, width - size);
    maxY = Math.max(0, height - size);
    clamp();
  }

  public void clamp() {
    x = Math.max(0, Math.min(x, maxX));
    y = Math.max(0, Math.min(y, maxY));
  }

  public void step(double seconds) {
    if (!Double.isFinite(seconds) || seconds <= 0) return;
    double dt = Math.min(.05, seconds);
    vy += gravity * dt;
    x += vx * dt;
    y += vy * dt;
    if (x < 0) {
      x = 0;
      vx = Math.abs(vx) * restitution;
    }
    if (x > maxX) {
      x = maxX;
      vx = -Math.abs(vx) * restitution;
    }
    if (y < 0) {
      y = 0;
      vy = Math.abs(vy) * restitution;
    }
    if (y > maxY) {
      y = maxY;
      vy = -Math.abs(vy) * restitution;
      if (Math.abs(vy) < 65) vy = 0;
      vx *= Math.exp(-2.5 * dt);
    }
    if (Math.abs(vx) < 2) vx = 0;
    resting = y >= maxY - .5 && Math.abs(vy) < 2 && Math.abs(vx) < 2;
  }

  public static void collide(PetPhysics a, PetPhysics b, double radius) {
    double dx = b.x - a.x, dy = b.y - a.y, distance = Math.hypot(dx, dy);
    if (distance >= radius || distance < .001) return;
    double nx = dx / distance,
        ny = dy / distance,
        relative = (b.vx - a.vx) * nx + (b.vy - a.vy) * ny;
    double push = (radius - distance) / 2;
    a.x -= nx * push;
    a.y -= ny * push;
    b.x += nx * push;
    b.y += ny * push;
    if (relative < 0) {
      double impulse = -(1 + Math.min(a.restitution, b.restitution)) * relative / 2;
      a.vx -= impulse * nx;
      a.vy -= impulse * ny;
      b.vx += impulse * nx;
      b.vy += impulse * ny;
    }
    a.clamp();
    b.clamp();
  }
}
