package org.ggp.base.util.propnet.architecture.components;

import org.ggp.base.util.propnet.architecture.Component;

/**
 * The And class is designed to represent logical AND gates.
 */
@SuppressWarnings("serial")
public final class And extends Component
{
	/**
	 * Returns true if and only if every input to the and is true.
	 *
	 * @see org.ggp.base.util.propnet.architecture.Component#getValue()
	 */
	@Override
	public boolean getValue()
	{
		for ( Component component : getInputs() )
		{
			if ( !component.getValue() )
			{
				return false;
			}
		}
		return true;
	}

	@Override
	public int getAmbiguousValue() {
		int retVal = 1;

		for (Component component : getInputs()) {
			int val = component.getAmbiguousValue();
			if (val == Component.AMBIGUOUS) {
				retVal = Component.AMBIGUOUS;
			} else if (val == 0) {
				return 0;
			}
		}

		return retVal;
	}

	/**
	 * @see org.ggp.base.util.propnet.architecture.Component#toString()
	 */
	@Override
	public String toString()
	{
		return toDot("invhouse", "grey", "AND");
	}

}
