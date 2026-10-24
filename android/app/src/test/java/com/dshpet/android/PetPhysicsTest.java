package com.dshpet.android;

import static org.junit.Assert.*;

import org.junit.Test;

public final class PetPhysicsTest {
  @Test
  public void thrownPetStaysInBoundsAndEventuallySettles() {
    PetPhysics body = new PetPhysics();
    body.x = 100;
    body.y = 100;
    body.vx = 1700;
    body.vy = -1200;
    body.bounds(400, 800, 128);
    for (int i = 0; i < 4000; i++) {
      body.step(1.0 / 60);
      assertTrue(body.x >= 0 && body.x <= 272);
      assertTrue(body.y >= 0 && body.y <= 672);
    }
    assertTrue(body.resting);
    assertEquals(672, body.y, .5);
  }

  @Test
  public void rotationClampsPositionsAndLongFramesDoNotTeleport() {
    PetPhysics body = new PetPhysics();
    body.x = 700;
    body.y = 900;
    body.bounds(300, 500, 128);
    assertEquals(172, body.x, .001);
    assertEquals(372, body.y, .001);
    body.x = 0;
    body.y = 0;
    body.vx = 100;
    body.gravity = 0;
    body.step(5);
    assertEquals(5, body.x, .001);
    body.step(Double.NaN);
    assertEquals(5, body.x, .001);
  }

  @Test
  public void collisionSeparatesApproachingBodiesAndDissipatesEnergy() {
    PetPhysics a = new PetPhysics(), b = new PetPhysics();
    a.bounds(1000, 1000, 100);
    b.bounds(1000, 1000, 100);
    a.x = 200;
    b.x = 250;
    a.vx = 100;
    b.vx = -100;
    PetPhysics.collide(a, b, 80);
    assertTrue(a.vx < 0);
    assertTrue(b.vx > 0);
    assertTrue(b.x - a.x >= 79.99);
    assertTrue(a.vx * a.vx + b.vx * b.vx < 20000);
  }
}
