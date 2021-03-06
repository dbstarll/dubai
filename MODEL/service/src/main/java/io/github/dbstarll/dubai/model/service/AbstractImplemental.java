package io.github.dbstarll.dubai.model.service;

import com.mongodb.client.model.Filters;
import io.github.dbstarll.dubai.model.collection.Collection;
import io.github.dbstarll.dubai.model.entity.Base;
import io.github.dbstarll.dubai.model.entity.Entity;
import io.github.dbstarll.dubai.model.entity.func.Defunctable;
import io.github.dbstarll.dubai.model.entity.info.Describable;
import io.github.dbstarll.dubai.model.entity.info.Namable;
import io.github.dbstarll.dubai.model.notify.NotifyType;
import io.github.dbstarll.dubai.model.service.ServiceFactory.GeneralValidateable;
import io.github.dbstarll.dubai.model.service.ServiceFactory.PositionValidation;
import io.github.dbstarll.dubai.model.service.validate.Validate;
import io.github.dbstarll.dubai.model.service.validate.ValidateException;
import io.github.dbstarll.dubai.model.service.validate.ValidateWrapper;
import io.github.dbstarll.dubai.model.service.validation.AbstractValidation;
import io.github.dbstarll.dubai.model.service.validation.GeneralValidation;
import io.github.dbstarll.dubai.model.service.validation.GeneralValidation.Position;
import io.github.dbstarll.dubai.model.service.validation.MultiValidation;
import io.github.dbstarll.dubai.model.service.validation.Validation;
import org.apache.commons.lang3.StringUtils;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.commons.lang3.Validate.*;

public abstract class AbstractImplemental<E extends Entity, S extends Service<E>> implements Implemental {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractImplemental.class);

    protected final S service;
    protected final Class<E> entityClass;
    private final Collection<E> collection;

    /**
     * 构建AbstractImplemental.
     *
     * @param service    service
     * @param collection collection
     */
    public AbstractImplemental(S service, Collection<E> collection) {
        this.service = notNull(service, "service is null");
        this.collection = notNull(collection, "collection is null");
        this.entityClass = collection.getEntityClass();
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        // do nothing
    }

    protected final Collection<E> getCollection() {
        return collection;
    }

    @SafeVarargs
    protected final E validateAndDelete(final ObjectId id, final Validate validate, final Validation<E>... validations)
            throws ValidateException {
        noNullElements(validations, "validations contains null element at index: %d");
        final Validate v = ValidateWrapper.wrap(validate);

        try {
            if (validations != null && validations.length > 0) {
                final E entity = collection.findById(id);
                if (entity == null) {
                    return null;
                }
                new MultiValidation<>(entityClass, validations).validate(entity, null, v);
            }
            if (!v.hasErrors()) {
                LOGGER.debug("validateAndDelete");
                final E deleted = collection.deleteById(id);
                onEntityDeleted(deleted, validate);
                return deleted;
            } else {
                LOGGER.debug("validateAndDelete with ActionErrors: {}, FieldErrors: {}", v.hasActionErrors(),
                        v.hasFieldErrors());
            }
        } catch (Throwable ex) {
            v.addActionError(ex.getMessage());
            LOGGER.error("validateAndDelete failed!", ex);
        }

        if (validate == null) {
            throw new ValidateException(v);
        } else {
            return null;
        }
    }

    /**
     * 对实体进行校验然后保存. 有以下几种情况：
     * <ol>
     * <li>校验通过，且有被修改的内容，返回更新后的实体</li>
     * <li>校验通过，但没有被修改的内容，返回null</li>
     * <li>校验未通过，且有{@link Validate}，在{@link Validate}中填充校验结果，返回null</li>
     * <li>校验未通过，且未设置{@link Validate}，抛出ValidateException</li>
     * </ol>
     *
     * @param entity      需要插入或更新的实体
     * @param newEntityId 插入时指定entity的id
     * @param validate    校验结果容器
     * @param validations 校验回调，实现对特定实体的具体校验内容
     * @return 返回更新后的实体，若未执行更新操作，则返回null
     * @throws ValidateException 如果校验未通过，且未设置校验结果容器，则抛出此异常
     */
    @SafeVarargs
    protected final E validateAndSave(final E entity, final ObjectId newEntityId, final Validate validate,
                                      final Validation<E>... validations) throws ValidateException {
        noNullElements(validations, "validations contains null element at index: %d");
        final Validate v = ValidateWrapper.wrap(validate);

        try {
            final boolean save = checkSave(entity, v, validations);
            if (!v.hasErrors()) {
                LOGGER.debug("validateAndSave with change: {}", save);
                if (save) {
                    final NotifyType notifyType = entity.getId() == null ? NotifyType.insert : NotifyType.update;
                    final E saved = collection.save(entity, newEntityId);
                    onEntitySaved(saved, validate, notifyType);
                    return saved;
                } else {
                    return null;
                }
            } else {
                LOGGER.debug("validateAndSave with ActionErrors: {}, FieldErrors: {}", v.hasActionErrors(), v.hasFieldErrors());
            }
        } catch (Throwable ex) {
            v.addActionError(ex.getMessage());
            LOGGER.error("validateAndSave failed!", ex);
        }

        if (validate == null) {
            throw new ValidateException(v);
        } else {
            return null;
        }
    }

    @SafeVarargs
    private final boolean checkSave(final E entity, final Validate v, final Validation<E>... validations) {
        if (entity == null) {
            v.addActionError("实体未设置");
        } else if (entity.getId() == null) {
            getValidation(validations).validate(entity, null, v);
            return true;
        } else {
            final E original = collection.original().findById(entity.getId());
            if (original == null) {
                v.addActionError("实体未找到");
            } else {
                getValidation(validations).validate(entity, original, v);
                return !v.hasErrors() && !entity.equals(original);
            }
        }
        return false;
    }

    @SafeVarargs
    private final Validation<E> getValidation(final Validation<E>... validations) {
        final MultiValidation<E> mv = new MultiValidation<>(entityClass);
        if (service instanceof GeneralValidateable) {
            @SuppressWarnings("unchecked") final GeneralValidateable<E> general = (GeneralValidateable<E>) service;
            final Iterable<PositionValidation<E>> generalValidations = general.generalValidations();
            addValidation(mv, generalValidations, Position.FIRST);
            addValidation(mv, generalValidations, Position.PRE);
            mv.addValidation(validations);
            addValidation(mv, generalValidations, Position.POST);
            addValidation(mv, generalValidations, Position.LAST);
        } else {
            mv.addValidation(validations);
        }
        return mv;
    }

    private void addValidation(final MultiValidation<E> mv, final Iterable<PositionValidation<E>> validations,
                               final Position position) {
        for (PositionValidation<E> pv : validations) {
            if (position == pv.getKey()) {
                mv.addValidation(pv.getValue());
            }
        }
    }

    @Deprecated
    protected void onEntitySaved(E entity, Validate validate) {
    }

    protected void onEntitySaved(E entity, Validate validate, NotifyType notifyType) {
    }

    protected void onEntityDeleted(E entity, Validate validate) {
    }

    protected final Bson aggregateMatchFilter(Bson filter) {
        if (Defunctable.class.isAssignableFrom(entityClass)) {
            final Bson defunctFilter = Filters.eq(Defunctable.FIELD_NAME_DEFUNCT, false);
            if (filter == null) {
                return defunctFilter;
            } else {
                return Filters.and(filter, defunctFilter);
            }
        } else {
            return filter;
        }
    }

    @GeneralValidation(Namable.class)
    public Validation<E> nameValidation() {
        return new NameValidation(2, 16);
    }

    @GeneralValidation(Describable.class)
    public Validation<E> descriptionValidation() {
        return new DescriptionValidation(50);
    }

    protected abstract class AbstractEntityValidation extends AbstractValidation<E> {
        public AbstractEntityValidation() {
            super(AbstractImplemental.this.entityClass);
        }
    }

    protected abstract class AbstractBaseEntityValidation<B extends Base>
            extends AbstractEntityValidation {
        public AbstractBaseEntityValidation(Class<B> baseClass) {
            isAssignableFrom(baseClass, entityClass);
        }

        @SuppressWarnings("unchecked")
        @Override
        public final void validate(E entity, E original, Validate validate) {
            validate((B) entity, (B) original, validate);
        }

        protected abstract void validate(B entity, B original, Validate validate);
    }

    protected class NameValidation extends AbstractBaseEntityValidation<Namable> {
        private final int minLength;
        private final int maxLength;

        public NameValidation(int minLength, int maxLength) {
            super(Namable.class);
            isTrue(minLength < 0 || maxLength >= minLength, "maxLength: %d 必须>= minLength: %d", maxLength, minLength);
            this.minLength = minLength;
            this.maxLength = maxLength;
        }

        @Override
        protected void validate(Namable entity, Namable original, Validate validate) {
            final String name = entity.getName();
            if (original == null || !StringUtils.equals(name, original.getName())) {
                if (StringUtils.isBlank(name)) {
                    validate.addFieldError(Namable.FIELD_NAME_NAME, "名称未设置");
                } else {
                    boolean lastWhitespace = false;
                    for (int i = 0, size = name.length(); i < size; i++) {
                        if (Character.isWhitespace(name.charAt(i))) {
                            if (i == 0) {
                                validate.addFieldError(Namable.FIELD_NAME_NAME, "名称不能以空字符开头");
                            } else if (i == size - 1) {
                                validate.addFieldError(Namable.FIELD_NAME_NAME, "名称不能以空字符结尾");
                            } else if (lastWhitespace) {
                                validate.addFieldError(Namable.FIELD_NAME_NAME, "名称不能包含连续的空字符");
                            } else {
                                lastWhitespace = true;
                            }
                        } else {
                            lastWhitespace = false;
                        }
                    }
                    if (minLength >= 0 && name.length() < minLength) {
                        validate.addFieldError(Namable.FIELD_NAME_NAME, "名称不能少于 " + minLength + " 字符");
                    } else if (maxLength >= 0 && name.length() > maxLength) {
                        validate.addFieldError(Namable.FIELD_NAME_NAME, "名称不能超过 " + maxLength + " 字符");
                    }
                }
            }
        }
    }

    protected class DescriptionValidation extends AbstractBaseEntityValidation<Describable> {
        private final int maxLength;

        public DescriptionValidation(int maxLength) {
            super(Describable.class);
            this.maxLength = maxLength;
        }

        @Override
        protected void validate(Describable entity, Describable original, Validate validate) {
            final String description = entity.getDescription();
            if (original == null ? description != null : !StringUtils.equals(description, original.getDescription())) {
                if (StringUtils.isBlank(description)) {
                    entity.setDescription(null);
                } else if (maxLength >= 0 && description.length() > maxLength) {
                    validate.addFieldError(Describable.FIELD_NAME_DESCRIPTION, "备注不能超过 " + maxLength + " 字符");
                }
            }
        }
    }
}
