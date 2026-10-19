package com.pricetrack.exchange.futures.math;

import java.math.BigInteger;

/** Exact isolated-margin arithmetic shared with FuturesMath.sol; all money/quantity is E18, price E8. */
public final class FuturesMath {
    public static final BigInteger PRICE_SCALE = BigInteger.TEN.pow(8);
    public static final BigInteger AMOUNT_LIMIT = BigInteger.TEN.pow(30);
    public static final BigInteger PRICE_LIMIT = BigInteger.TEN.pow(20);
    private static final BigInteger TWENTY = BigInteger.valueOf(20);

    private FuturesMath() {}
    public enum Side { LONG, SHORT }
    public record Mark(BigInteger notional, BigInteger unrealized, BigInteger equity,
                       BigInteger maintenance, BigInteger realized, BigInteger badDebt) {
        public boolean atRisk() { return equity.compareTo(maintenance) <= 0; }
    }

    public static BigInteger quantity(BigInteger margin, int leverage, BigInteger entryPrice) {
        amount(margin); price(entryPrice);
        if (leverage != 2 && leverage != 5 && leverage != 10)
            throw new IllegalArgumentException("Unsupported futures leverage");
        BigInteger q = margin.multiply(BigInteger.valueOf(leverage)).multiply(PRICE_SCALE).divide(entryPrice);
        amount(q);
        return q;
    }

    public static Mark mark(Side side, BigInteger quantity, BigInteger entryPrice,
                            BigInteger collateral, BigInteger markPrice) {
        if (side == null) throw new IllegalArgumentException("Missing futures side");
        amount(quantity); amount(collateral); price(entryPrice); price(markPrice);
        BigInteger product = quantity.multiply(markPrice);
        BigInteger signed = quantity.multiply(markPrice.subtract(entryPrice));
        if (side == Side.SHORT) signed = signed.negate();
        BigInteger pnl = floorDivide(signed, PRICE_SCALE);
        BigInteger equity = collateral.add(pnl);
        BigInteger maintenance = ceilDivide(product, PRICE_SCALE.multiply(TWENTY));
        return new Mark(product.divide(PRICE_SCALE), pnl, equity, maintenance,
                pnl.max(collateral.negate()), equity.negate().max(BigInteger.ZERO));
    }

    public static BigInteger floorDivide(BigInteger numerator, BigInteger denominator) {
        if (denominator.signum() <= 0) throw new IllegalArgumentException("Nonpositive divisor");
        BigInteger[] qr = numerator.divideAndRemainder(denominator);
        return numerator.signum() < 0 && qr[1].signum() != 0 ? qr[0].subtract(BigInteger.ONE) : qr[0];
    }

    private static BigInteger ceilDivide(BigInteger n, BigInteger d) {
        return n.add(d).subtract(BigInteger.ONE).divide(d);
    }
    private static void amount(BigInteger n) { bounded(n, AMOUNT_LIMIT); }
    private static void price(BigInteger n) { bounded(n, PRICE_LIMIT); }
    private static void bounded(BigInteger n, BigInteger limit) {
        if (n == null || n.signum() <= 0 || n.compareTo(limit) >= 0)
            throw new IllegalArgumentException("Futures value outside supported range");
    }
}
