package org.ggp.base.util.statemachine;

import java.util.Arrays;

public class PropNetMachineState extends MachineState {

    private final boolean[] baseProps;

    public PropNetMachineState(boolean[] baseProps)
    {
        this.baseProps = baseProps;
    }

	@Override
	public MachineState clone() {
		return new PropNetMachineState(baseProps);
	}

	public boolean [] getBaseProps() {
		return baseProps;
	}

	/* Utility methods */
    @Override
	public int hashCode()
    {
        return baseProps.hashCode();
    }

    @Override
	public String toString()
    {
   		return baseProps.toString();
    }

    @Override
	public boolean equals(Object o)
    {
        if ((o != null) && (o instanceof PropNetMachineState))
        {
            PropNetMachineState state = (PropNetMachineState) o;
            return Arrays.equals(baseProps, state.baseProps);
        }

        return false;
    }
}
