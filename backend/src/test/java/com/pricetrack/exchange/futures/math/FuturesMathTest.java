package com.pricetrack.exchange.futures.math;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class FuturesMathTest {
    @Test void sameGoldenFileAsSolidity() throws Exception {
        var vectors = new ObjectMapper().readTree(Path.of("../contracts/test/fixtures/futures-math.json").toFile()).get("vectors");
        assertThat(vectors.size()).isEqualTo(8);
        for (var v : vectors) {
            var m = FuturesMath.mark(FuturesMath.Side.values()[v.get("side").asInt()],
                    v.get("quantity").bigIntegerValue(), v.get("entry").bigIntegerValue(),
                    v.get("collateral").bigIntegerValue(), v.get("price").bigIntegerValue());
            assertThat(m.unrealized()).isEqualTo(v.get("pnl").bigIntegerValue());
            assertThat(m.equity()).isEqualTo(v.get("equity").bigIntegerValue());
            assertThat(m.maintenance()).isEqualTo(v.get("maintenance").bigIntegerValue());
            assertThat(m.realized()).isEqualTo(v.get("realized").bigIntegerValue());
            assertThat(m.badDebt()).isEqualTo(v.get("badDebt").bigIntegerValue());
            assertThat(m.atRisk()).isEqualTo(m.equity().compareTo(m.maintenance()) <= 0);
        }
    }
    @Test void allThreeLeveragesAndBothSides() {
        BigInteger margin = BigInteger.TEN.pow(23), entry = new BigInteger("7500000000000");
        for (int l : new int[]{2,5,10}) {
            BigInteger q = FuturesMath.quantity(margin,l,entry);
            assertThat(q.multiply(entry)).isLessThanOrEqualTo(margin.multiply(BigInteger.valueOf(l)).multiply(FuturesMath.PRICE_SCALE));
            for (var side : FuturesMath.Side.values()) {
                var unchanged = FuturesMath.mark(side,q,entry,margin,entry);
                assertThat(unchanged.unrealized()).isZero();
                assertThat(unchanged.atRisk()).isFalse();
                var up = FuturesMath.mark(side,q,entry,margin,entry.multiply(BigInteger.valueOf(101)).divide(BigInteger.valueOf(100)));
                assertThat(up.unrealized().signum()).isEqualTo(side == FuturesMath.Side.LONG ? 1 : -1);
            }
        }
    }
    @Test void randomIsolatedLossAndHouseConservation() {
        var random = new Random(20261018);
        for (int i=0;i<2000;i++) {
            BigInteger c=BigInteger.valueOf(1+random.nextInt(1000000)).multiply(BigInteger.TEN.pow(18));
            BigInteger p0=BigInteger.valueOf(1+random.nextInt(1000000)).multiply(FuturesMath.PRICE_SCALE);
            BigInteger price=BigInteger.valueOf(1+random.nextInt(1000000)).multiply(FuturesMath.PRICE_SCALE);
            BigInteger q=FuturesMath.quantity(c,new int[]{2,5,10}[i%3],p0);
            var m=FuturesMath.mark(FuturesMath.Side.values()[i%2],q,p0,c,price);
            assertThat(c.add(m.realized())).isGreaterThanOrEqualTo(BigInteger.ZERO);
            assertThat(c.add(m.realized())).isEqualTo(m.equity().add(m.badDebt()));
            assertThat(m.realized()).isEqualTo(m.unrealized().max(c.negate()));
            assertThat(m.badDebt()).isEqualTo(m.equity().negate().max(BigInteger.ZERO));
        }
    }
    @Test void invalidLeverageAndBoundsFailClosed() {
        for (int l : new int[]{0,1,3,20}) assertThatIllegalArgumentException().isThrownBy(
                ()->FuturesMath.quantity(BigInteger.ONE,l,BigInteger.ONE));
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.quantity(FuturesMath.AMOUNT_LIMIT,2,BigInteger.ONE));
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.quantity(BigInteger.ONE,2,FuturesMath.PRICE_LIMIT));
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.quantity(BigInteger.ONE,2,BigInteger.TEN.pow(19)));
        assertThat(FuturesMath.floorDivide(BigInteger.valueOf(-1),BigInteger.valueOf(100))).isEqualTo(BigInteger.valueOf(-1));
    }
    @Test void maximumSupportedProductStaysInsideSigned256AndZeroRejected() {
        BigInteger maxQ=FuturesMath.AMOUNT_LIMIT.subtract(BigInteger.ONE);
        BigInteger maxP=FuturesMath.PRICE_LIMIT.subtract(BigInteger.ONE);
        for(var side:FuturesMath.Side.values()) {
            var m=FuturesMath.mark(side,maxQ,BigInteger.ONE,maxQ,maxP);
            assertThat(m.unrealized().abs()).isLessThan(BigInteger.ONE.shiftLeft(255));
            assertThat(m.realized()).isGreaterThanOrEqualTo(maxQ.negate());
        }
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.mark(FuturesMath.Side.LONG,BigInteger.ZERO,BigInteger.ONE,BigInteger.ONE,BigInteger.ONE));
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.mark(FuturesMath.Side.LONG,BigInteger.ONE,BigInteger.ZERO,BigInteger.ONE,BigInteger.ONE));
        assertThatIllegalArgumentException().isThrownBy(()->FuturesMath.mark(FuturesMath.Side.LONG,maxQ,maxP,BigInteger.ZERO,maxP));
    }
}
