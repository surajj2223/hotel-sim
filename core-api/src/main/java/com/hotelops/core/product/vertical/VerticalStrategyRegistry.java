package com.hotelops.core.product.vertical;

import com.hotelops.core.common.enums.Vertical;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the correct {@link VerticalStrategy} for a given {@link Vertical}.
 *
 * Package B registers its strategy beans; this registry finds them by vertical.
 * If no strategy is registered for a vertical (e.g. during early development),
 * a {@link MissingStrategyException} is thrown so the gap is surfaced loudly.
 */
@Component
public class VerticalStrategyRegistry {

    private final Map<Vertical, VerticalStrategy> strategies;

    public VerticalStrategyRegistry(List<VerticalStrategy> strategyList) {
        strategies = new EnumMap<>(Vertical.class);
        for (VerticalStrategy s : strategyList) {
            if (s instanceof VerticalStrategyRegistration r) {
                strategies.put(r.vertical(), s);
            }
        }
    }

    public VerticalStrategy forVertical(Vertical vertical) {
        VerticalStrategy s = strategies.get(vertical);
        if (s == null) {
            throw new MissingStrategyException(vertical);
        }
        return s;
    }

    /** Marker interface: strategy implementations declare their vertical via this. */
    public interface VerticalStrategyRegistration {
        Vertical vertical();
    }

    public static class MissingStrategyException extends RuntimeException {
        public MissingStrategyException(Vertical vertical) {
            super("No VerticalStrategy registered for: " + vertical);
        }
    }
}
