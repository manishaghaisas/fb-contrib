
public class CD_Sample {
    Child c = new Child();;
    
    public void cdTest() {
        c.childTest();
    }
}

class Child {
    SubChild sc = new SubChild();
    
    public void childTest() {
        sc.subChildTest();
    }
}

class SubChild {
    CD_Sample cds = new CD_Sample();
    
    public void subChildTest() {
        cds.toString();
    }
}

class Mammooth {
	private int x;
	
	static class Builder {
		private int x;
		
		public void setX(int x) {
			this.x = x;
		}
		
		public Mammooth build() {
			Mammooth m = new Mammooth();
			m.x = x;
			return m;
		}
	}
}
