package net.atomarea.flowx.xmpp.jingle.stanzas;

import net.atomarea.flowx.xml.Element;

public class Reason extends Element {
	private Reason(String name) {
		super(name);
	}

	public Reason() {
		super("reason");
	}
}
