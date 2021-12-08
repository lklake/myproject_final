package ticketingsystem;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
class SeatReference{
	int count;
	int seatIndex;
}


class RouteDs {
	int seatNum;
	AtomicInteger[] atomicSeatBits;
	Ticket[][] seatTickets;
	int[] nonAtomicSeatBits;
	ReentrantReadWriteLock lock;

	RouteDs(int seatNum, int stationNum) {
		this.seatNum = seatNum;
		atomicSeatBits = new AtomicInteger[seatNum];
		nonAtomicSeatBits = new int[seatNum];
		for (int i = 0; i < seatNum; i++) {
			atomicSeatBits[i] = new AtomicInteger(0);
			nonAtomicSeatBits[i] = 0;
		}
		this.seatTickets = new Ticket[seatNum][];
		for (int i = 0; i < seatNum; i++) {
			this.seatTickets[i] = new Ticket[stationNum];
		}
		lock = new ReentrantReadWriteLock();
	}
}

public class TicketingDS implements TicketingSystem {
	ArrayList<RouteDs> routes;
	AtomicLong ticketId;
	int coachesPerRoute;
	int seatsPerCoach;
	// final int seatMask;

	TicketingDS(int routenum, int coachnum, int seatnum, int stationnum, int threadnum) {
		routes = new ArrayList<>();
		for (int routeIdx = 0; routeIdx < routenum; routeIdx++) {
			routes.add(new RouteDs(coachnum * seatnum, stationnum));
		}
		this.coachesPerRoute = coachnum;
		this.seatsPerCoach = seatnum;
		// 这个原子变量是一个竞争的节点，但我觉得冲突访问的机率不大。
		ticketId = new AtomicLong(0);
		// seatMask = (1 << this.seatsPerCoach * this.coachesPerRoute) - 1;
	}

	public Ticket buyTicket(String passenger, int route, int departure, int arrival) {
		RouteDs routeDs = routes.get(route - 1);
		int nonAtomic;
		// 00000110000
		int target = (1 << (arrival - 1)) - (1 << (departure - 1));
		int newValue;
		for (int seatIdx = 0; seatIdx < routeDs.seatNum; seatIdx++) {
			nonAtomic = routeDs.nonAtomicSeatBits[seatIdx];
			if ((nonAtomic & target) == 0) {
				newValue = nonAtomic | target;
				if (routeDs.atomicSeatBits[seatIdx].compareAndSet(nonAtomic, newValue)) {
					Ticket ticket = buildTicket(ticketId.getAndIncrement(), passenger, route,
							(seatIdx / this.seatsPerCoach) + 1,
							(seatIdx % this.seatsPerCoach) + 1, departure, arrival);
					routeDs.seatTickets[seatIdx][departure - 1] = ticket;
					routeDs.nonAtomicSeatBits[seatIdx] = newValue;
					return ticket;
				}
			}
		}
		try{
			routeDs.lock.writeLock().lock();
			for (int seatIdx = 0; seatIdx < routeDs.seatNum; seatIdx++) {
				nonAtomic = routeDs.nonAtomicSeatBits[seatIdx];
				if ((nonAtomic & target) == 0) {
					newValue = nonAtomic | target;
					if (routeDs.atomicSeatBits[seatIdx].compareAndSet(nonAtomic, newValue)) {
						Ticket ticket = buildTicket(ticketId.getAndIncrement(), passenger, route,
								(seatIdx / this.seatsPerCoach) + 1,
								(seatIdx % this.seatsPerCoach) + 1, departure, arrival);
						routeDs.seatTickets[seatIdx][departure - 1] = ticket;
						routeDs.nonAtomicSeatBits[seatIdx] = newValue;
						return ticket;
					}
				}
			}
		}finally{
			routeDs.lock.writeLock().unlock();
		}

		return null;
	}

	public int inquiry(int route, int departure, int arrival) {
		int result = 0;
		RouteDs routeDs = routes.get(route - 1);
		int nonAtomic;
		int target = (1 << (arrival - 1)) - (1 << (departure - 1));
		for (int seatIdx = 0; seatIdx < routeDs.seatNum; seatIdx++) {
			nonAtomic = routeDs.nonAtomicSeatBits[seatIdx];
			if ((nonAtomic & target) == 0) {
				result++;
			}
		}
		return result;
	}

	public boolean refundTicket(Ticket ticket) {
		boolean result = false;
		// 防止伪造的ticket数据结构中有异常数据导致发生索引越界异常
		try {
			int route = ticket.route;
			int arrivalIdx = ticket.arrival -1;
			int departureIdx = ticket.departure - 1;
			int seatIdx = (ticket.coach - 1) * this.seatsPerCoach + ticket.seat - 1;
			RouteDs routeDs = routes.get(route - 1);
			int nonAtomic;
			int target = (1 << arrivalIdx) - (1 << departureIdx);
			int newValue;
			nonAtomic = routeDs.nonAtomicSeatBits[seatIdx];
			if ((nonAtomic & target) == target) {
				// 这里对target按位取反后，需要和seatMask进行与操作，将没有被使用的高位置零
				// 因为target是正数，按位取反后，高位均为1，而本实现只用低位来保存座位是否被占用
				// 如果因target高位为1，导致atomicSeatBits高位为1，那么接下来查询这个座位是否
				// 被占用，将一直返回非零数，表示被占用，这是不对的。
				//修正：不需要将target高位清零，因为nonAtomic高位一定为0，那么与操作后，无论~target
				// 高位如何，结果的高位都为0
				
				if (!ticketEqual(routeDs.seatTickets[seatIdx][departureIdx], ticket)) {
					return false;
				}
				newValue = nonAtomic & (~target);
				try{
					routeDs.lock.readLock().lock();
					if (routeDs.atomicSeatBits[seatIdx].compareAndSet(nonAtomic, newValue)) {
						routeDs.nonAtomicSeatBits[seatIdx] = newValue;
						routeDs.seatTickets[seatIdx][departureIdx]=null;
						return true;
					}
				}finally{
					routeDs.lock.readLock().unlock();
				}
			}
		} catch (Exception e) {
		    result = false;
		}
		return result;
	}

	public boolean buyTicketReplay(Ticket ticket){return true;};
	public boolean refundTicketReplay(Ticket ticket){return true;};


	private Ticket buildTicket(long tid, String passenger, int route, int coach, int seat, int departure, int arrival) {
		Ticket ticket = new Ticket();
		ticket.tid = tid;
		ticket.passenger = passenger;
		ticket.route = route;
		ticket.coach = coach;
		ticket.seat = seat;
		ticket.departure = departure;
		ticket.arrival = arrival;
		return ticket;
	}

	private boolean ticketEqual(Ticket ticket1, Ticket ticket2) {
		return ticket1.tid == ticket2.tid && ticket1.passenger.equals(ticket2.passenger) &&
				ticket1.route == ticket2.route && ticket1.coach == ticket2.coach &&
				ticket1.seat == ticket2.seat && ticket1.departure == ticket2.departure
				&& ticket1.arrival == ticket2.arrival;
	}
}
