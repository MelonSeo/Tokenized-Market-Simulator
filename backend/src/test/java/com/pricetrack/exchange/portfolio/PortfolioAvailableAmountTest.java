package com.pricetrack.exchange.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pricetrack.exchange.market.MarketPriceService;
import com.pricetrack.exchange.wallet.UserBalance;
import com.pricetrack.exchange.wallet.UserBalanceRepository;

class PortfolioAvailableAmountTest {
    @Test void exactAvailableStringExcludesLocksAndPreservesExistingNumericFields() throws Exception {
        var repository = mock(UserBalanceRepository.class);
        var prices = mock(MarketPriceService.class);
        var token = new UserBalance(3L, "mSEC");
        token.setAmount(new BigDecimal("1.328161833310517989"));
        token.setLockedAmount(new BigDecimal("0.328000000000000000"));
        when(repository.findAllByUserIdOrderBySymbol(3L)).thenReturn(List.of(token));
        when(prices.currentPrice()).thenReturn(new BigDecimal("75000"));
        var result = new PortfolioService(repository, prices).portfolio(3L);
        assertThat(result.tokenAvailableAmount()).isEqualTo("1.000161833310517989");
        assertThat(result.tokenBalance()).isEqualByComparingTo(token.getAmount());
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(result));
        assertThat(json.path("tokenAvailableAmount").isTextual()).isTrue();
        assertThat(json.path("tokenAvailableAmount").asText()).isEqualTo("1.000161833310517989");
        assertThat(json.path("tokenBalance").isNumber()).isTrue();
        verify(repository).findAllByUserIdOrderBySymbol(3L);
        verifyNoMoreInteractions(repository);
    }

    @Test void smallestResidualAndZeroRemainPlainDecimal() {
        var repository = mock(UserBalanceRepository.class);
        var prices = mock(MarketPriceService.class);
        when(prices.currentPrice()).thenReturn(BigDecimal.ONE);
        var token = new UserBalance(3L, "mSEC");
        token.setAmount(new BigDecimal("0.000000000000000001"));
        when(repository.findAllByUserIdOrderBySymbol(3L)).thenReturn(List.of(token));
        var service = new PortfolioService(repository, prices);
        assertThat(service.portfolio(3L).tokenAvailableAmount()).isEqualTo("0.000000000000000001");
        token.setLockedAmount(token.getAmount());
        assertThat(new BigDecimal(service.portfolio(3L).tokenAvailableAmount())).isZero();
        when(repository.findAllByUserIdOrderBySymbol(4L)).thenReturn(List.of());
        assertThat(service.portfolio(4L).tokenAvailableAmount()).isEqualTo("0");
    }
}
