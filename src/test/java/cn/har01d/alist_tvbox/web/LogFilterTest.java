package cn.har01d.alist_tvbox.web;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogFilterTest {

    @Test
    void imageProxyRequestsAreExcludedFromQueryLogging() {
        LogFilter filter = new LogFilter();
        MockHttpServletRequest queryImage = new MockHttpServletRequest("GET", "/images");
        queryImage.setQueryString("url=https%3A%2F%2Fexample.com%2Fposter.jpg%3Ftoken%3Dsecret");
        MockHttpServletRequest storedImage = new MockHttpServletRequest("GET", "/images/42");

        assertEquals(true, ReflectionTestUtils.invokeMethod(filter, "skip", queryImage));
        assertEquals(true, ReflectionTestUtils.invokeMethod(filter, "skip", storedImage));
    }
}
