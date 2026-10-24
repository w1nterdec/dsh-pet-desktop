package com.dshpet.android;

import static org.junit.Assert.*;

import java.io.IOException;
import org.junit.Test;

public class PackPathsTest {
  @Test
  public void acceptsPortablePaths() throws Exception {
    assertEquals("动作/idle.png", PackPaths.safe("动作/idle.png"));
    assertTrue(PackPaths.image("idle.WEBP"));
    assertFalse(PackPaths.image("pet.jar"));
    assertEquals(PackPaths.key("É.png"), PackPaths.key("E\u0301.PNG"));
  }

  @Test
  public void rejectsTraversalAndAmbiguousNames() {
    for (String path :
        new String[] {
          "../pet.png",
          "/pet.png",
          "C:/pet.png",
          "a\\pet.png",
          "a//pet.png",
          "a/./pet.png",
          "NUL.png",
          "a./pet.png",
          "a /pet.png",
          "a\u0000.png",
          "a\nb\nc.png"
        }) {
      assertThrows(path, IOException.class, () -> PackPaths.safe(path));
    }
  }
}
