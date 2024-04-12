package com.netgrif.mksr.petrinet.responsebodies;

import com.netgrif.mksr.petrinet.web.CustomUriController;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.server.mvc.WebMvcLinkBuilder;

public class CustomUriNodeResources extends CollectionModel<CustomUriNode> {

    public CustomUriNodeResources(Iterable<CustomUriNode> content) {
        super(content);
        buildLinks();
    }

    private void buildLinks() {
        add(WebMvcLinkBuilder.linkTo(WebMvcLinkBuilder.methodOn(CustomUriController.class)
                .getRoot()).withRel("root"));
    }
}
