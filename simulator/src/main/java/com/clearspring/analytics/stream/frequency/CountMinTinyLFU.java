package com.clearspring.analytics.stream.frequency;

/**
 * A version of the TinyLFU sketch based on a regular conservative update sketch. 
 * The difference is that anytime the sum of counters reach a predefined values
 * we divide all counters by 2 in what is called a reset operation. 
 * 
 * @author gilga
 *  [1] TinyLFU: A Highly Efficient Cache Admission Policy
 *  http://www.cs.technion.ac.il/~gilga/TinyLFU_PDP2014.pdf

 */
public class CountMinTinyLFU extends ConservativeAddSketch{
	final int sampleSize;
	int nrItems;
    public CountMinTinyLFU(int depth, int width, int seed, int samplesize) {
        super(depth, width, seed);
        sampleSize = samplesize;
        nrItems =0;
    }
    public CountMinTinyLFU(double epsOfTotalCount, double confidence, int seed, int samplesize) {
    	super(epsOfTotalCount,confidence,seed);
    	sampleSize = samplesize;
        nrItems =0;
    }

    @Override
    public void add(long item, long count) {
    	nrItems+=count;
    	resetIfNeeded();
    	super.add(item, count);
    }
    @Override
    public void add(String item, long count) {
    	nrItems+=count;
    	resetIfNeeded();
    	super.add(item, count);
    }
	private void resetIfNeeded() {
		if(nrItems>sampleSize)
    	{
			nrItems/=2;
    		for(int i=0; i<this.depth;i++)
    		{
    			for(int j=0; j<this.width;j++)
    			{
    				nrItems-=table[i][j]&1;
    				table[i][j]>>>=1;
    			}
    		}
    	}
	}

	

}
