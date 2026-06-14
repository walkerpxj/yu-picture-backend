package com.yupi.yupicturebackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.BCrypt;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.yupi.yupicturebackend.exception.BusinessException;
import com.yupi.yupicturebackend.exception.ErrorCode;
import com.yupi.yupicturebackend.model.dto.user.UserLoginRequest;
import com.yupi.yupicturebackend.model.dto.user.UserQueryRequest;
import com.yupi.yupicturebackend.model.dto.user.UserRegisterRequest;
import com.yupi.yupicturebackend.model.entity.User;
import com.yupi.yupicturebackend.model.enums.UserRoleEnum;
import com.yupi.yupicturebackend.model.vo.LoginUserVO;
import com.yupi.yupicturebackend.model.vo.UserVO;
import com.yupi.yupicturebackend.service.UserService;
import com.yupi.yupicturebackend.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static com.yupi.yupicturebackend.constant.UserConstant.USER_LOGIN_STATE;

/**
 * @author WALKER
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2025-09-13 15:47:59
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 登录失败计数的 Redis key 前缀
     */
    private static final String LOGIN_FAIL_KEY_PREFIX = "login:fail:";

    /**
     * 允许的最大连续登录失败次数，超过则锁定
     */
    private static final int MAX_LOGIN_FAIL_COUNT = 10;

    /**
     * 锁定时长（秒）
     */
    private static final long LOGIN_LOCK_SECONDS = 30;

    /**
     * 用户注册
     *
     * @param userRegisterRequest
     * @return
     */
    @Override
    public long userRegister(UserRegisterRequest userRegisterRequest) {
        //1.校验参数
        if (userRegisterRequest.getUserPassword() == null || userRegisterRequest.getUserAccount() == null ||
                userRegisterRequest.getCheckPassword() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userRegisterRequest.getUserAccount().length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号过短");
        }
        if (userRegisterRequest.getUserPassword().length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码过短");
        }
        if (!userRegisterRequest.getUserPassword().equals(userRegisterRequest.getCheckPassword())) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "两次输入密码不一致，请重新输入！");
        }
        //2.检查用户账户是否和数据库中已有的重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userRegisterRequest.getUserAccount());
        long count = this.baseMapper.selectCount(queryWrapper);
        if (count > 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复，请重新输入！");
        }
        //3.密码一定要加密
        String encryptPassword = getEncryptPassword(userRegisterRequest.getUserPassword());
        //4.插入数据到数据库中
        User user = new User();
        user.setUserAccount(userRegisterRequest.getUserAccount());
        user.setUserPassword(encryptPassword);
        user.setUserName(userRegisterRequest.getUserAccount());
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult;
        try {
            saveResult = this.save(user);
        } catch (DuplicateKeyException e) {
            // 兜底并发场景：两个请求同时通过了上面的 count 校验，靠数据库唯一索引 uk_userAccount 拦截
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "账号重复，请重新输入！");
        }
        if (!saveResult) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误!");
        }
        return user.getId();
    }

    @Override
    public LoginUserVO userLogin(UserLoginRequest userLoginRequest, HttpServletRequest request) {
        //1.校验
        if (userLoginRequest.getUserPassword() == null || userLoginRequest.getUserAccount() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "参数为空");
        }
        if (userLoginRequest.getUserAccount().length() < 4) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户账号错误");
        }
        if (userLoginRequest.getUserPassword().length() < 8) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户密码错误");
        }
        //1.5 登录失败限流：若该账号连续失败次数已达上限，直接拒绝
        String failKey = LOGIN_FAIL_KEY_PREFIX + userLoginRequest.getUserAccount();
        String failCountStr = stringRedisTemplate.opsForValue().get(failKey);
        if (failCountStr != null && Integer.parseInt(failCountStr) >= MAX_LOGIN_FAIL_COUNT) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "登录失败次数过多，请稍后再试");
        }
        //2.根据账号查询用户（BCrypt 每次输出不同，不能再用密文作为查询条件）
        User user = lambdaQuery().eq(User::getUserAccount, userLoginRequest.getUserAccount())
                .one();
        //3.校验密码（兼容 BCrypt 新格式与 MD5 旧格式）
        if (user == null || !matchesPassword(userLoginRequest.getUserPassword(), user.getUserPassword())) {
            //登录失败：累加失败计数并刷新锁定时间窗口
            recordLoginFail(failKey);
            log.info("user login failed,userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或者密码错误");
        }
        //登录成功：清除失败计数
        stringRedisTemplate.delete(failKey);
        //3.5 平滑迁移：若库中仍是旧版 MD5，登录成功后自动升级为 BCrypt
        if (!user.getUserPassword().startsWith("$2")) {
            User upgrade = new User();
            upgrade.setId(user.getId());
            upgrade.setUserPassword(getEncryptPassword(userLoginRequest.getUserPassword()));
            this.updateById(upgrade);
        }
        //4。保存用户的登录态（只存 userId，避免把密码哈希等敏感信息写入 Session/Redis）
        request.getSession().setAttribute(USER_LOGIN_STATE, user.getId());
        return this.getLoginUserVO(user);
    }

    @Override
    public User getLoginUser(HttpServletRequest request) {
        //判断是否登录（Session 中只存了 userId）
        Object userIdObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userIdObj == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        Long userId = (Long) userIdObj;
        //从数据库中查询最新的用户信息
        User currentUser = this.getById(userId);
        if (currentUser == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        return currentUser;
    }

    /**
     * 获取加密后的密码
     * <p>
     * 使用 BCrypt 算法，每次加密都会自动生成随机盐，
     * 因此同一明文多次调用的返回值不同，验证时需用 {@link BCrypt#checkpw} 比对。
     *
     * @param userPassword 用户密码
     * @return 加密后的密码（形如 $2a$10$...）
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        return BCrypt.hashpw(userPassword);
    }

    /**
     * 旧版 MD5 加密（仅用于兼容存量密码的平滑迁移，请勿用于新密码）
     */
    private String getLegacyMd5Password(String userPassword) {
        final String SALT = "yingdaomayi";
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes());
    }

    /**
     * 校验原始密码与数据库存储的密码是否匹配。
     * 兼容两种格式：BCrypt（新）与 MD5（旧）。
     *
     * @return 是否匹配
     */
    private boolean matchesPassword(String rawPassword, String storedPassword) {
        if (StrUtil.isBlank(storedPassword)) {
            return false;
        }
        // BCrypt 哈希以 $2a$ / $2b$ / $2y$ 开头
        if (storedPassword.startsWith("$2")) {
            return BCrypt.checkpw(rawPassword, storedPassword);
        }
        // 否则按旧版 MD5 比对
        return getLegacyMd5Password(rawPassword).equals(storedPassword);
    }

    /**
     * 记录一次登录失败：失败计数 +1，并重置锁定时间窗口。
     * 首次失败时设置过期时间，使每次失败都把锁定窗口顺延 {@link #LOGIN_LOCK_SECONDS} 秒。
     *
     * @param failKey 该账号对应的 Redis 计数 key
     */
    private void recordLoginFail(String failKey) {
        Long count = stringRedisTemplate.opsForValue().increment(failKey);
        // 每次失败都刷新过期时间，避免计数永久残留
        stringRedisTemplate.expire(failKey, LOGIN_LOCK_SECONDS, TimeUnit.SECONDS);
        log.info("login fail count for key={} is {}", failKey, count);
    }

    /**
     * 获得脱敏类的用户信息
     *
     * @param user 用户信息
     * @return
     */
    @Override
    public LoginUserVO getLoginUserVO(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtil.copyProperties(user, loginUserVO);
        return loginUserVO;
    }

    @Override
    public UserVO getUserVO(User user) {
        if (user == null) {
            return null;
        }
        UserVO UserVO = new UserVO();
        BeanUtil.copyProperties(user, UserVO);
        return UserVO;
    }

    /**
     * 获取脱敏后的用户列表
     *
     * @param userList
     * @return
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollUtil.isEmpty(userList)) {
            return new ArrayList<>();
        }

        return userList.stream()
                .map(this::getUserVO)
                .collect(Collectors.toList());
    }

    @Override
    public boolean userLogout(HttpServletRequest request) {
        Object userObj = request.getSession().getAttribute(USER_LOGIN_STATE);
        if (userObj == null) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR);
        }
        request.getSession().removeAttribute(USER_LOGIN_STATE);
        return true;
    }

    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(ObjUtil.isNotNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

}




