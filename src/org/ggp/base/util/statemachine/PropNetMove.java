package org.ggp.base.util.statemachine;

import org.ggp.base.util.gdl.grammar.GdlTerm;

@SuppressWarnings("serial")
public class PropNetMove extends Move {

	private int inputProp;

	public PropNetMove(int inputProp)
    {
		super(null);
        this.inputProp = inputProp;
    }

	public PropNetMove(Integer inputProp, GdlTerm gdlTerm) {
		super(gdlTerm);
		this.inputProp = inputProp;
	}

	public int getInputProp() {
		return inputProp;
	}

    @Override
    public boolean equals(Object o)
    {
        if ((o != null) && (o instanceof PropNetMove)) {
            PropNetMove move = (PropNetMove) o;
            return move.inputProp == inputProp;
        }

        return false;
    }

    @Override
    public int hashCode()
    {
        return inputProp;
    }

    @Override
    public String toString()
    {
        return "Input Prop: "+inputProp;
    }
}
