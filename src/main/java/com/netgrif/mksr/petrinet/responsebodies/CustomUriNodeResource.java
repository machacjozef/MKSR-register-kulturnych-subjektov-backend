package com.netgrif.mksr.petrinet.responsebodies;

import com.netgrif.application.engine.petrinet.domain.UriNode;
import com.netgrif.mksr.petrinet.web.CustomUriController;
import org.springframework.hateoas.EntityModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;

public class CustomUriNodeResource extends EntityModel<CustomUriNode> {

    public CustomUriNodeResource(CustomUriNode content) {
        super(content);
        buildLinks();
    }

    private void buildLinks() {
        UriNode content = getContent();
        if (content != null) {
            add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder
                            .methodOn(CustomUriController.class).getRoot())
                    .withSelfRel());

            add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder
                            .methodOn(CustomUriController.class).getOne(content.getUriPath()))
                    .withSelfRel());
        }
    }
}
