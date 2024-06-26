package com.netgrif.mksr.petrinet.service.interfaces;

import com.netgrif.application.engine.auth.domain.IUser;
import com.netgrif.mksr.petrinet.web.requestbodies.UriCountRequest;

import java.util.Locale;
import java.util.Map;

public interface IUriCountService {

    Map<String, Integer> count(UriCountRequest request, IUser user, Locale locale);
}
