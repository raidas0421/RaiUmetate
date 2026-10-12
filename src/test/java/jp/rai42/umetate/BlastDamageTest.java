package jp.rai42.umetate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BlastDamageTest {
   @Test
   void powerFourDealsThirtyDamageAtZeroDistance() {
      assertEquals(30, BlastDamage.calculate(4, 0, 1));
   }

   @Test
   void powerFourKeepsDistanceFalloffAndStopsAtBlastRadius() {
      assertEquals(24, BlastDamage.calculate(4, 1, 1));
      assertEquals(20, BlastDamage.calculate(4, 2, 1));
      assertEquals(15, BlastDamage.calculate(4, 3, 1));
      assertEquals(11, BlastDamage.calculate(4, 4, 1));
      assertEquals(8, BlastDamage.calculate(4, 5, 1));
      assertEquals(5, BlastDamage.calculate(4, 6, 1));
      assertEquals(3, BlastDamage.calculate(4, 7, 1));
      assertEquals(0, BlastDamage.calculate(4, 8, 1));
   }

   @Test
   void exposureStillReducesDamage() {
      assertEquals(11, BlastDamage.calculate(4, 0, 0.5));
      assertEquals(0, BlastDamage.calculate(4, 0, 0));
   }
}