package no.metatrack.server.stats;

import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.DefaultValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class StatisticsControllerTest {
    private StatisticsService service;
    private StatisticsController controller;

    @BeforeEach
    void setUp() {
        service = mock(StatisticsService.class);
        controller = new StatisticsController();
        controller.statisticsService = service;
    }

    @Test
    void delegatesValidPaginationAndReturnsResponseContract() {
        DailySampleCountPage expected = new DailySampleCountPage(
                List.of(new DailySampleCount(LocalDate.of(2026, 8, 11), 7)),
                1,
                20,
                35,
                2);
        when(service.getDailySampleCounts(1, 20)).thenReturn(expected);

        DailySampleCountPage result = controller.getSamplesByDate(1, 20);

        assertSame(expected, result);
        assertEquals(LocalDate.of(2026, 8, 11), result.items().getFirst().date());
        assertEquals(7, result.items().getFirst().sampleCount());
        assertEquals(1, result.page());
        assertEquals(20, result.size());
        assertEquals(35, result.totalElements());
        assertEquals(2, result.totalPages());
        verify(service).getDailySampleCounts(1, 20);
    }

    @Test
    void documentsPaginationDefaults() throws NoSuchMethodException {
        Method method = StatisticsController.class.getMethod("getSamplesByDate", int.class, int.class);

        assertEquals("0", method.getParameters()[0].getAnnotation(DefaultValue.class).value());
        assertEquals("20", method.getParameters()[1].getAnnotation(DefaultValue.class).value());
    }

    @Test
    void delegatesDefaultPaginationValues() {
        controller.getSamplesByDate(0, 20);

        verify(service).getDailySampleCounts(0, 20);
    }

    @Test
    void rejectsNegativePage() {
        assertThrows(BadRequestException.class, () -> controller.getSamplesByDate(-1, 20));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsNonPositiveSize() {
        assertThrows(BadRequestException.class, () -> controller.getSamplesByDate(0, 0));
        assertThrows(BadRequestException.class, () -> controller.getSamplesByDate(0, -1));

        verifyNoInteractions(service);
    }

    @Test
    void rejectsSizeAboveMaximum() {
        assertThrows(BadRequestException.class,
                () -> controller.getSamplesByDate(0, StatisticsController.MAX_PAGE_SIZE + 1));

        verifyNoInteractions(service);
    }

    @Test
    void acceptsMaximumSize() {
        controller.getSamplesByDate(0, StatisticsController.MAX_PAGE_SIZE);

        verify(service).getDailySampleCounts(0, StatisticsController.MAX_PAGE_SIZE);
    }
}