package de.doridian.yiffbungee.bans;
import de.doridian.yiffbungee.database.DatabaseConnectionPool;

import java.lang.ref.SoftReference;
import java.net.InetAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;

public class BanResolver {
	private static HashMap<String, Integer> playerIDs = new HashMap<>();
	private static HashMap<Integer, String> playerUUIDs = new HashMap<>();
	private static HashMap<Integer, SoftReference<Ban>> playerBans = new HashMap<>();

	private static final long BAN_MAX_AGE_MILLIS = 60 * 1000;

	public static Ban getBan(String username, String uuid) {
		return getBan(username, uuid, true);
	}

	public static void addIPForPlayer(String username, String uuid, InetAddress address) {
		if(address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress())
			return;

		int userID = getUserID(username, uuid, true);

		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("REPLACE INTO user_ips (player, ip, time) VALUES (?, ?, UNIX_TIMESTAMP())");
			preparedStatement.setInt(1, userID);
			preparedStatement.setBytes(2, address.getAddress());
			preparedStatement.execute();
			preparedStatement.close();
			connection.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static Collection<String> getPossibleAltsForPlayer(String username, String uuid) {
		int userID = getUserID(username, uuid);
		if(userID < 1)
			return null;

		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("SELECT player FROM user_ips WHERE ip IN (SELECT ip FROM user_ips WHERE player = ?)");
			preparedStatement.setInt(1, userID);
			ResultSet resultSet = preparedStatement.executeQuery();
			HashMap<Integer, String> alts = new HashMap<>();
			while(resultSet.next()) {
				int player = resultSet.getInt("player");
				if(player == userID)
					continue;
				String ply = getUserByID(player);
				if(ply == null || ply.isEmpty()) {
					ply = "#" + player;
					System.out.println("INVALID PLAYER #" + player);
				}
				alts.put(player, ply);
			}
			preparedStatement.close();
			connection.close();
			return alts.values();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public static void addBan(Ban ban) {
		deleteBan(ban);
		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO bans (reason, admin, player, type, time) VALUES (?, ?, ?, ?, ?)");
			preparedStatement.setString(1, ban.getReason());
			preparedStatement.setInt(2, ban.getAdminID());
			preparedStatement.setInt(3, ban.getUserID());
			preparedStatement.setString(4, ban.getType());
			preparedStatement.setInt(5, ban.getTime());
			preparedStatement.execute();
			preparedStatement.close();
			connection.close();

			playerBans.put(ban.getUserID(), new SoftReference<>(ban));
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	public static void deleteBan(Ban ban) {
		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM bans WHERE player = ?");
			preparedStatement.setInt(1, ban.getUserID());
			preparedStatement.execute();
			preparedStatement.close();
			connection.close();

			playerBans.remove(ban.getUserID());
		} catch(Exception e) {
			e.printStackTrace();
		}
	}

	protected static Ban getBan(String username, String uuid, boolean useCaches) {
		int userID = getUserID(username, uuid);
		if(userID < 1)
			return null;

		if(playerBans.containsKey(userID)) {
			SoftReference<Ban> cachedBanRef = playerBans.get(userID);
			if(cachedBanRef != null) {
				Ban cachedBan = cachedBanRef.get();
				if(useCaches && cachedBan != null && ((System.currentTimeMillis() - cachedBan.retrievalTime) < BAN_MAX_AGE_MILLIS)) {
					return cachedBan;
				} else {
					playerBans.remove(userID);
				}
			} else {
				playerBans.remove(userID);
			}
		}
		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("SELECT * FROM bans WHERE player = ?");
			preparedStatement.setInt(1, userID);
			ResultSet resultSet = preparedStatement.executeQuery();
			Ban ret = null;
			if(resultSet.next()) {
				ret = new Ban(resultSet.getString("reason"), resultSet.getInt("admin"), resultSet.getInt("player"), resultSet.getString("type"), resultSet.getInt("time"));
				playerBans.put(userID, new SoftReference<>(ret));
			}
			preparedStatement.close();
			connection.close();
			return ret;
		} catch(Exception e) {
			e.printStackTrace();
			return new Ban("Database failure", 0, 0, "invalid", 0);
		}
	}

	public static String getUserByID(int id) {
		if(playerUUIDs.containsKey(id)) {
			return playerUUIDs.get(id);
		}
		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement = connection.prepareStatement("SELECT name FROM players WHERE id = ?");
			preparedStatement.setInt(1, id);
			ResultSet resultSet = preparedStatement.executeQuery();
			String ret = null;
			if(resultSet.next()) {
				ret = resultSet.getString("name");
				playerIDs.put(ret, id);
				playerUUIDs.put(id, ret);
			}
			preparedStatement.close();
			connection.close();
			return ret;
		} catch(Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	public static int getUserID(String username, String uuid) {
		return getUserID(username, uuid, false);
	}

	public static int getUserID(String username, String uuid, boolean create) {
		uuid = uuid != null ? uuid.toLowerCase() : null;
		username = username != null ? username.toLowerCase() : null;
		if(playerIDs.containsKey(uuid)) {
			return playerIDs.get(uuid);
		}
		try {
			Connection connection = DatabaseConnectionPool.instance.getConnection();
			PreparedStatement preparedStatement;
			if(uuid != null) {
				preparedStatement = connection.prepareStatement("SELECT id, name, uuid FROM players WHERE uuid = ?");
				preparedStatement.setString(1, uuid);
			} else {
				preparedStatement = connection.prepareStatement("SELECT id, name, uuid FROM players WHERE name = ?");
				preparedStatement.setString(1, username);
			}
			ResultSet resultSet = preparedStatement.executeQuery();
			int ret = 0;
			if(resultSet.next()) {
				ret = resultSet.getInt("id");
				uuid = resultSet.getString("uuid");
				playerIDs.put(uuid, ret);
				playerUUIDs.put(ret, uuid);
				if(!resultSet.getString("name").equals(username)) {
					username = resultSet.getString("name");
					preparedStatement.close();
					preparedStatement = connection.prepareStatement("UPDATE players SET name = ? WHERE uuid = ?");
					preparedStatement.setString(1, username);
					preparedStatement.setString(2, uuid);
					preparedStatement.execute();
				}
			} else if(create) {
				if(uuid == null)
					throw new RuntimeException("Cannot create player without UUID");
				preparedStatement.close();
				preparedStatement = connection.prepareStatement("INSERT INTO players (name, uuid) VALUES (?, ?)", Statement.RETURN_GENERATED_KEYS);
				preparedStatement.setString(1, username);
				preparedStatement.setString(2, uuid);
				ret = preparedStatement.executeUpdate();
				resultSet = preparedStatement.getGeneratedKeys();
				if(resultSet.next()) {
					ret = resultSet.getInt(1);
					playerIDs.put(uuid, ret);
					playerUUIDs.put(ret, uuid);
				}
			}
			preparedStatement.close();
			connection.close();
			return ret;
		} catch(Exception e) {
			return 0;
		}
	}
}